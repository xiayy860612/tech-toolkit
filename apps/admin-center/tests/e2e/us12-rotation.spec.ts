import { type APIRequestContext, type BrowserContext, type Page } from "@playwright/test";
import { ADMIN, expect, login, test } from "./support/fixtures";
import { Contexts, createUserViaUi, PASSWORD, runId, settle, widthOf } from "./support/responsive";
import {
  adminCenterLogs,
  captureAdminCenterLogs,
  e2eServiceKey,
  restoreKey,
  rotateKey,
  setAdminCenterKey,
  waitForAdminCenter,
} from "./support/stack";

/**
 * US12：服务密钥轮换与 Admin Center 的配置错误处理（FR-008、FR-042 ~ FR-044；SC-016；quickstart S19）。
 * 前提：栈已按 devops/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）；
 * UAM 已实现双哈希与 403（T178 ~ T181），`devops/local/scripts/rotate-key.sh`（T183）存在，
 * 且支持 `rotate`（不停机轮换，结束时栈处于新密钥）与 `restore`（回到 devops/local/e2e.env）两个动作。
 *
 * ① 持续访问下完成一次轮换：一个已登录的专用用户每 ~200 ms 请求受保护页面，同时跑 `rotate-key.sh rotate`。
 *    判定：0 个用户被登出（任何一次响应是跳到 /login 或 /session/expire）；0 个请求因密钥失败（Admin Center 日志里没有一条
 *    `admin-center.uam.misconfigured` 告警——重建 Admin Center 会丢旧容器的日志，所以全程持续收集）；轮换后同一个 cookie 仍有效、
 *    且新密钥下写操作可用。
 *    不能记作「因密钥失败」的两类失败**单独统计并如实报告**，不掩盖：
 *    - transport：Admin Center 自己被重建的那一小段连接中断（连接被拒 / 重置 / 502 ~ 504）；
 *    - unavailable：单实例的 UAM 被重建时 Admin Center 显示「服务暂不可用」（UAM 不可达，不是密钥问题；用户没被登出）。
 *    它们的数量只作为注解和控制台输出，不作判定条件（判定条件见上）；唯一的要求是流量确实跑起来了、且轮换结束后恢复到全部成功。
 * ② 配置错误：把 Admin Center 的服务密钥故意配错（`setAdminCenterKey`），已登录的用户不被登出（cookie 不变）、页面显示
 *    「服务暂不可用」+「登录状态已保留」、写操作「操作未生效」且没有生效、登录页提示「服务暂不可用」（不是账号名或密码错误）；
 *    Admin Center 日志有告警且不含密钥；改回后**不需要重新登录**，同一个会话继续可用。
 *
 * 该规格会重建栈里的容器，且与视口无关：只在 w1440 项目里跑，其它视口项目跳过（用 `--project=w1440`）。
 * `finally` 总是把栈还原到 e2e.env（`restoreKey()` / `setAdminCenterKey(undefined)`），否则后续规格会失败。
 *
 * 造数：专用用户 `密钥轮换…`（用户名）与 `e2e.r12.…`（账号名，≤ 32 个字符）；名字刻意不含其它规格造数用户的子串（多账号用户、无账号用户、
 * 已停用用户、分页用户、在线者、离线者、建档员、停权对象、强退对象、改密者、终结验证、响应式、原型核对、甲号、乙号、丙号）。
 * 流量与配置错误只用专用用户的会话；ADMIN 的会话只用来建用户，不被拿去做流量，也不改它的密码。
 */

const COOKIE_NAME = process.env.E2E_SESSION_COOKIE_NAME ?? "asid"; // devops/local/e2e.env 的 SESSION_COOKIE_NAME
/** 故意配错的服务密钥：形状上不是真密钥（没有真实前缀），明显是测试值。 */
const WRONG_KEY = "wrong-service-key-for-e2e-misconfig-test";
const ALERT_EVENT = "admin-center.uam.misconfigured";

const ctxs = new Contexts();

/** 会重建栈里的容器，与视口无关：只在 w1440 项目里跑一次。 */
const onlyOnce = () => test.skip(test.info().project.name !== "w1440", "只在 w1440 项目里跑一次");
test.afterEach(async () => {
  await ctxs.closeAll();
});

function names(page: Page, tag: string): { userName: string; accountName: string } {
  const suffix = `${runId}${widthOf(page)}${tag}`;
  return { userName: `密钥轮换${suffix}`, accountName: `e2e.r12.${suffix}` };
}

const sessionCookie = async (context: BrowserContext) => (await context.cookies()).find((c) => c.name === COOKIE_NAME);

/** 页面级提示（Next 自带的路由播报器也是 role=alert，所以按文案过滤）。 */
const alertWith = (page: Page, text: string | RegExp) => page.getByRole("main").getByRole("alert").filter({ hasText: text });
const statusWith = (page: Page, text: string | RegExp) => page.getByRole("status").filter({ hasText: text });

type Outcome = "ok" | "unavailable" | "transport" | "logout" | "other";

/** 不经页面、不跟随重定向地请求一个受保护页面，并归类结果（请求共享所属上下文的 cookie）。 */
async function probe(request: APIRequestContext, path: string): Promise<Outcome> {
  try {
    const res = await request.get(path, { maxRedirects: 0, timeout: 5_000 });
    const status = res.status();
    if (status >= 300 && status < 400) return /\/(login|session\/expire)/.test(res.headers()["location"] ?? "") ? "logout" : "other";
    if (status === 502 || status === 503 || status === 504) return "transport";
    if (status !== 200) return "other";
    const body = await res.text();
    if (body.includes("服务暂不可用")) return "unavailable";
    return body.includes("用户管理") ? "ok" : "other";
  } catch {
    return "transport"; // 连接被拒 / 重置 / 超时：Admin Center 容器被重建的那一小段
  }
}

/** 每 ~200 ms 轮流请求几个受保护页面，记下每次的结果与时间，直到 `stop()`。 */
function startTraffic(request: APIRequestContext) {
  const paths = ["/users", "/sessions", `/users?q=${encodeURIComponent("密钥轮换")}`];
  const results: Array<{ at: number; outcome: Outcome }> = [];
  let stopped = false;
  const loop = (async () => {
    for (let n = 0; !stopped; n++) {
      results.push({ at: Date.now(), outcome: await probe(request, paths[n % paths.length]) });
      await new Promise((resolve) => setTimeout(resolve, 200));
    }
  })();
  return {
    async stop() {
      stopped = true;
      await loop;
      const count = (outcome: Outcome) => results.filter((r) => r.outcome === outcome).length;
      return {
        results,
        total: results.length,
        ok: count("ok"),
        logout: count("logout"),
        transport: count("transport"),
        unavailable: count("unavailable"),
        other: count("other"),
      };
    },
  };
}

test.describe("US12 · 服务密钥轮换（S19 ①）", () => {
  test("持续访问下完成一次轮换：0 个用户被登出、0 个请求因密钥失败；同一会话轮换后仍可用", async ({ browser, baseURL, page }) => {
    onlyOnce();
    test.setTimeout(20 * 60_000);
    await login(page);
    await expect(page).toHaveURL(/\/users$/);
    const op = names(page, "r");
    await createUserViaUi(page, op);
    const opPage = await ctxs.signIn(browser, baseURL, page, op.accountName);
    const cookieBefore = (await sessionCookie(opPage.context()))?.value;
    expect(cookieBefore).toBeTruthy();
    // 轮换前基线：专用用户的流量页面是通的
    expect(await probe(opPage.context().request, "/users")).toBe("ok");

    const logs = captureAdminCenterLogs();
    const traffic = startTraffic(opPage.context().request);
    let report: Awaited<ReturnType<typeof traffic.stop>> | undefined;
    let alertLines: string[] = [];
    let rotateOutput = "";
    let postRotation: { cookieSame: boolean; url: string } | undefined;
    const written = names(page, "w");
    try {
      try {
        rotateOutput = await rotateKey();
        // 轮换后（栈处于新密钥）：Admin Center 已稳定，流量全部恢复成功
        await waitForAdminCenter();
        await expect(async () => expect(await probe(opPage.context().request, "/users")).toBe("ok")).toPass({ timeout: 60_000 });
      } finally {
        report = await traffic.stop();
        alertLines = await logs.stop();
      }

      // 同一个 cookie 仍有效：用同一个浏览器上下文打开页面，没有被带去登录页
      await opPage.goto("/users");
      await expect(opPage.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();
      postRotation = { cookieSame: (await sessionCookie(opPage.context()))?.value === cookieBefore, url: opPage.url() };
      // 新密钥下写操作可用（证明 Admin Center 用的确实是新密钥，而不只是读缓存）
      await createUserViaUi(opPage, written);
      expect((await sessionCookie(opPage.context()))?.value).toBe(cookieBefore);
    } finally {
      // 无论哪一步失败，都把栈还原到 e2e.env（栈共享）；流量与日志收集也确保已停
      report ??= await traffic.stop();
      await restoreKey();
      await waitForAdminCenter();
    }

    // 还原（再轮换回去）之后，同一个会话依然有效
    await expect(async () => {
      await opPage.goto("/users");
      await expect(opPage.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 60_000 });
    await expect(opPage).toHaveURL(/\/users$/);
    expect((await sessionCookie(opPage.context()))?.value).toBe(cookieBefore);

    // ---- 判定与如实报告 ----
    const summary =
      `请求 ${report.total} 次：成功 ${report.ok}，登出 ${report.logout}，` +
      `transport（Admin Center 重建期间的连接中断）${report.transport}，` +
      `unavailable（UAM 重建期间的「服务暂不可用」）${report.unavailable}，其它 ${report.other}`;
    console.log(`[S19 ①] ${summary}`);
    test.info().annotations.push({ type: "S19-①", description: summary });
    test.info().annotations.push({ type: "rotate-key.sh", description: rotateOutput.split("\n").slice(-20).join("\n") });

    expect(report.total, "持续访问确实在轮换期间运行了").toBeGreaterThan(20);
    expect(report.logout, `0 个用户被登出（${summary}）`).toBe(0);
    expect(report.other, `没有出现无法归类的响应（${summary}）`).toBe(0);
    // 0 个请求因密钥失败：Admin Center 日志里没有「UAM 拒绝了 Admin Center」告警（旧容器的日志已在重建时丢失，所以全程持续收集过）
    expect(alertLines.filter((l) => l.includes(ALERT_EVENT)), "没有因密钥产生的 misconfigured 告警").toEqual([]);
    expect(alertLines.join("\n")).not.toContain(e2eServiceKey());
    expect(postRotation).toMatchObject({ cookieSame: true });
    expect(postRotation?.url).toMatch(/\/users$/);
  });
});

test.describe("US12 · Admin Center 密钥配错（S19 ②）", () => {
  test("已登录用户不被登出、cookie 不清；页面「服务暂不可用」、写操作「操作未生效」；改回后无需重新登录", async ({ browser, baseURL, page }) => {
    onlyOnce();
    test.setTimeout(15 * 60_000);
    await login(page);
    await expect(page).toHaveURL(/\/users$/);
    const op = names(page, "m");
    const target = names(page, "t"); // 被「停用」的对象（不会真的被停用）
    const created = names(page, "c"); // 被「新建」的对象（不会真的被创建）
    await createUserViaUi(page, op);
    const targetId = await createUserViaUi(page, target);
    const opPage = await ctxs.signIn(browser, baseURL, page, op.accountName);
    const cookieBefore = (await sessionCookie(opPage.context()))!.value;

    // 配错之前先把两个写入口摆好（受保护页面在无法确认会话时不渲染表单，所以只能先打开再配错）
    await opPage.goto("/users/new");
    await opPage.getByLabel("用户名", { exact: true }).fill(created.userName);
    await opPage.getByLabel("账号名", { exact: true }).fill(created.accountName);
    await opPage.getByLabel("初始密码", { exact: true }).fill(PASSWORD);

    const detailPage = await opPage.context().newPage();
    await detailPage.goto(`/users/${targetId}`);
    await detailPage.getByRole("button", { name: "停用用户" }).click();
    const disableDialog = detailPage.getByRole("alertdialog", { name: `停用用户「${target.userName}」？` });
    await expect(disableDialog).toBeVisible();
    await settle(disableDialog);

    let alertLines: string[] = [];
    await setAdminCenterKey(WRONG_KEY);
    try {
      // 写操作：「操作未生效」，绝不显示成功
      await opPage.getByRole("button", { name: /^创建/ }).click();
      await expect(alertWith(opPage, "操作未生效")).toBeVisible({ timeout: 20_000 });
      await expect(opPage).toHaveURL(/\/users\/new$/);
      await expect(opPage.getByText("用户已创建")).toHaveCount(0);

      await disableDialog.getByRole("button", { name: "停用", exact: true }).click();
      await expect(alertWith(detailPage, "操作未生效")).toBeVisible({ timeout: 20_000 });
      await expect(detailPage.getByText(/已停用，其会话已结束/)).toHaveCount(0);

      // 读：横幅「服务暂不可用」+ 登录状态已保留；不跳登录页，cookie 原封不动
      for (const path of ["/users", "/sessions", `/users/${targetId}`, "/account/password"]) {
        await opPage.goto(path);
        await expect(statusWith(opPage, "服务暂不可用，请稍后重试。")).toBeVisible({ timeout: 20_000 });
        await expect(opPage.getByText("你的登录状态已保留，服务恢复后无需重新登录。")).toBeVisible();
        await expect(opPage).toHaveURL(new RegExp(`${path.replace(/[/?]/g, "\\$&")}$`));
        expect((await sessionCookie(opPage.context()))?.value).toBe(cookieBefore);
      }

      // 登录页：提示服务暂不可用（不是「账号名或密码错误」），没有会话。用一个不存在的账号：就算配置错误意外失效也不会顶掉任何真实会话
      const anon = await ctxs.open(browser, baseURL, page);
      await login(anon, `e2e.nobody.${runId}`, PASSWORD);
      await expect(alertWith(anon, "服务暂不可用，请稍后重试")).toBeVisible({ timeout: 20_000 });
      await expect(alertWith(anon, /账号名或密码错误/)).toHaveCount(0);
      await expect(anon).toHaveURL(/\/login/);
      expect(await sessionCookie(anon.context())).toBeUndefined();

      // 运维告警：Admin Center 日志有 misconfigured 事件（401 client_unauthenticated），且不含任何密钥 / 会话标识
      alertLines = await adminCenterLogs();
    } finally {
      await setAdminCenterKey(undefined); // 无论断言是否失败，都要把密钥改回去（栈共享）
    }

    const alerts = alertLines.filter((l) => l.includes(ALERT_EVENT));
    expect(alerts.length, "Admin Center 日志里有配置错误告警").toBeGreaterThan(0);
    expect(alerts.some((l) => l.includes("client_unauthenticated"))).toBe(true);
    const logged = alertLines.join("\n");
    for (const secret of [WRONG_KEY, e2eServiceKey(), cookieBefore, ADMIN.password, PASSWORD]) expect(logged).not.toContain(secret);

    // 改回后：同一个会话继续可用，不需要重新登录，cookie 没变
    await expect(async () => {
      await opPage.goto("/users");
      await expect(opPage.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 90_000 });
    await expect(opPage).toHaveURL(/\/users$/);
    expect((await sessionCookie(opPage.context()))?.value).toBe(cookieBefore);

    // 刚才的写操作都没有生效：对象仍是「正常」；同一个会话现在能成功新建（密钥已恢复，且全程没重新登录）
    await opPage.goto(`/users/${targetId}`);
    await expect(opPage.getByRole("region", { name: "基本信息" })).toContainText("正常");
    await createUserViaUi(opPage, created);
    expect((await sessionCookie(opPage.context()))?.value).toBe(cookieBefore);
  });
});
