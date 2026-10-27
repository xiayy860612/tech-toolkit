import AxeBuilder from "@axe-core/playwright";
import { type Browser, type BrowserContext, type Page } from "@playwright/test";
import { expect, login, test } from "./support/fixtures";
import { uamCli } from "./support/stack";

/**
 * US7：已登录用户修改自己的密码（quickstart S9；FR-026、FR-027；SC-007）。
 * 前提：栈已按 devops/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）；
 * UAM 已实现 US7（`POST /v1/accounts/{accountId}/password`，T139 ~ T141）。
 *
 * 造数：每个用例经「新建用户」界面（US4）建一个全新的用户及账号 A（需要时再用部署期命令 add-account 追加账号 B，密码只经标准输入），
 * 再在**独立的浏览器上下文**里以该用户登录后改密——绝不碰 ADMIN 的密码。
 * 名字带本次运行与视口的后缀，重复执行与六个视口项目互不冲突；名字刻意不含其它规格造数用户的子串
 * （多账号用户、无账号用户、已停用用户、分页用户、在线者、离线者、建档员、停权对象、强退对象、甲号、乙号、丙号），避免串到它们的搜索与计数。
 *
 * 账号与会话一一对应：同一账号的新登录会让旧会话失效，所以「新上下文验证旧 / 新密码」放在用例最后。
 * 「当前密码连续错误限制」按（账号, 来源地址）计数，每个用例用独立的用户与来源地址，互不串。
 * 「两次输入不一致」在 Admin Center 本地拦截、不调 UAM：浏览器侧无法直接观察服务端不发请求，这里只断言字段旁的原因与不改密
 * （「不调 UAM」由单元测试 password-actions.test.ts 覆盖）。
 */

const SC007_BUDGET_MS = 60_000;
const OLD_PASSWORD = "E2e-Old-Pass-1";
const NEW_PASSWORD = "E2e-New-Pass-2";
const runId = Date.now().toString(36);

const widthOf = (page: Page) => page.viewportSize()?.width ?? 1280;

interface Subject {
  userName: string;
  /** 账号 A（建用户时创建，用它登录并改密）。 */
  accountA: string;
  /** 账号 B（`withSecondAccount` 才有）：同一用户的另一个账号，不应受影响。 */
  accountB: string;
}

/** 以 ADMIN 身份经界面建一个全新的用户（账号 A，初始密码 OLD_PASSWORD）。账号名 ≤ 32 个字符且只含 `[A-Za-z0-9_.-]`。 */
async function createSubject(admin: Page, tag: string, withSecondAccount = false): Promise<Subject> {
  const suffix = `${runId}${widthOf(admin)}${tag}`;
  const subject = { userName: `改密者${suffix}`, accountA: `e2e.d7.${suffix}a`, accountB: `e2e.d7.${suffix}b` };
  await admin.goto("/users/new");
  await admin.getByLabel("用户名", { exact: true }).fill(subject.userName);
  await admin.getByLabel("账号名", { exact: true }).fill(subject.accountA);
  await admin.getByLabel("初始密码", { exact: true }).fill(OLD_PASSWORD);
  await admin.getByRole("button", { name: /^创建/ }).click();
  await expect(admin).toHaveURL(/\/users\/[0-9a-f-]{36}\?created=1$/);
  if (withSecondAccount) {
    const userId = new URL(admin.url()).pathname.split("/").pop()!;
    uamCli(["add-account", "--user-id", userId, "--account-name", subject.accountB], `${OLD_PASSWORD}\n`);
  }
  return subject;
}

const opened: BrowserContext[] = [];

/**
 * 新浏览器上下文不继承项目配置：baseURL、视口、来源地址头（x-forwarded-for）都显式带上，
 * 且每个上下文用不同的来源地址，避免登录与改密的失败限制串到其它用例。
 */
async function newContext(browser: Browser, baseURL: string | undefined, like: Page): Promise<Page> {
  const ip = `198.51.100.${20 + Math.floor(Math.random() * 200)}`;
  const context = await browser.newContext({
    baseURL,
    viewport: like.viewportSize() ?? undefined,
    extraHTTPHeaders: { "x-forwarded-for": ip },
  });
  opened.push(context);
  return context.newPage();
}

/** 在新上下文里用账号登录并确认进入了受保护页面。 */
async function signIn(browser: Browser, baseURL: string | undefined, like: Page, account: string, password: string): Promise<Page> {
  const page = await newContext(browser, baseURL, like);
  await login(page, account, password);
  await expect(page).toHaveURL(/\/users$/);
  return page;
}

const field = (page: Page, label: "当前密码" | "新密码" | "确认新密码") => page.getByLabel(label, { exact: true });
const submit = (page: Page) => page.getByRole("button", { name: /^修改/ });
// Next 自带的路由播报器也是 role=alert：限定在 main 内取表单级提示
const formAlert = (page: Page) => page.getByRole("main").getByRole("alert");

async function fillForm(page: Page, values: { current?: string; next?: string; confirm?: string }) {
  await field(page, "当前密码").fill(values.current ?? OLD_PASSWORD);
  await field(page, "新密码").fill(values.next ?? NEW_PASSWORD);
  await field(page, "确认新密码").fill(values.confirm ?? values.next ?? NEW_PASSWORD);
}

/**
 * 提交并等表单落定：Server Action 返回后 React 会重置表单（密码从不回填），
 * 不等它就填下一轮，值会被后到的重置抹掉，等于提交了空表单。
 */
async function submitAndSettle(page: Page) {
  await submit(page).click();
  await expect(submit(page)).not.toHaveText(/修改中/);
  for (const label of ["当前密码", "新密码", "确认新密码"] as const) await expect(field(page, label)).toHaveValue("");
}

const seriousViolations = async (page: Page) =>
  (await new AxeBuilder({ page }).analyze()).violations.filter((v) => v.impact === "serious" || v.impact === "critical");

test.afterEach(async () => {
  await Promise.all(opened.splice(0).map((context) => context.close()));
});

test.describe("S9：入口与表单", () => {
  test("用户菜单「修改密码」进入表单；字段、说明、autocomplete；无整页横向滚动；axe 无严重违规；触控目标不小于 44px", async ({
    page,
    browser,
    baseURL,
  }) => {
    await login(page);
    await expect(page).toHaveURL(/\/users$/);
    const subject = await createSubject(page, "a");
    const user = await signIn(browser, baseURL, page, subject.accountA, OLD_PASSWORD);

    await user.getByRole("button", { name: new RegExp(subject.userName) }).click();
    const menu = user.getByRole("menu");
    await expect(menu).toBeVisible();
    // 菜单有进入动画：等动画结束再点击与测量
    await menu.evaluate((el) => Promise.all(el.getAnimations().map((animation) => animation.finished)));
    await user.getByRole("menuitem", { name: "修改密码" }).click();

    await expect(user).toHaveURL(/\/account\/password$/);
    await expect(user.getByRole("heading", { level: 1, name: "修改密码" })).toBeVisible();
    // 菜单收起有淡出动画：等它从页面里消失再跑 axe（否则 axe 会按淡出中途的半透明颜色算对比度）
    await expect(user.getByRole("menu")).toHaveCount(0);
    await expect(user.getByRole("main")).toContainText(subject.accountA);
    await expect(field(user, "当前密码")).toHaveAttribute("autocomplete", "current-password");
    await expect(field(user, "新密码")).toHaveAttribute("autocomplete", "new-password");
    await expect(field(user, "确认新密码")).toHaveAttribute("autocomplete", "new-password");
    for (const label of ["当前密码", "新密码", "确认新密码"] as const) {
      await expect(field(user, label)).toHaveAttribute("type", "password");
    }
    await expect(user.getByText("至少 10 位，且同时包含字母和数字，不能与当前密码相同。")).toBeVisible();

    const overflow = await user.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    expect(await seriousViolations(user)).toEqual([]);
    for (const control of [field(user, "当前密码"), field(user, "新密码"), field(user, "确认新密码"), submit(user), user.getByRole("link", { name: "取消" })]) {
      expect((await control.boundingBox())!.height).toBeGreaterThanOrEqual(43.5);
    }

    await user.getByRole("link", { name: "取消" }).click();
    await expect(user).toHaveURL(/\/users$/);
  });
});

test.describe("S9：被拒的修改（不改密码，字段旁给出原因，不回填）", () => {
  test("当前密码错、新密码弱、与当前相同、两次输入不一致；之后旧密码仍可登录", async ({ page, browser, baseURL }) => {
    await login(page);
    await expect(page).toHaveURL(/\/users$/);
    const subject = await createSubject(page, "b");
    const user = await signIn(browser, baseURL, page, subject.accountA, OLD_PASSWORD);
    await user.goto("/account/password");

    // 当前密码错（1 次，远低于限制阈值）
    await fillForm(user, { current: "Wrong-Current-9" });
    await submitAndSettle(user);
    await expect(field(user, "当前密码")).toHaveAttribute("aria-invalid", "true");
    await expect(field(user, "当前密码")).toHaveAccessibleDescription("当前密码不正确");
    for (const label of ["当前密码", "新密码", "确认新密码"] as const) await expect(field(user, label)).toHaveValue("");
    await expect(user.locator("body")).not.toContainText(NEW_PASSWORD);
    await expect(user.locator("body")).not.toContainText("Wrong-Current-9");

    // 新密码弱
    await fillForm(user, { next: "weak1" });
    await submitAndSettle(user);
    await expect(field(user, "新密码")).toHaveAccessibleDescription("新密码至少 10 位，且同时包含字母和数字");
    await expect(field(user, "新密码")).toHaveValue("");

    // 长度够但缺数字
    await fillForm(user, { next: "onlyletterspassword" });
    await submitAndSettle(user);
    await expect(field(user, "新密码")).toHaveAccessibleDescription("新密码至少 10 位，且同时包含字母和数字");

    // 与当前密码相同
    await fillForm(user, { next: OLD_PASSWORD });
    await submitAndSettle(user);
    await expect(field(user, "新密码")).toHaveAccessibleDescription("新密码不能与当前密码相同");

    // 两次输入的新密码不一致（本地拦截）
    await fillForm(user, { confirm: `${NEW_PASSWORD}x` });
    await submitAndSettle(user);
    await expect(field(user, "确认新密码")).toHaveAccessibleDescription("两次输入的新密码不一致");
    await expect(field(user, "新密码")).not.toHaveAttribute("aria-invalid", "true");

    // 必填为空
    await submitAndSettle(user);
    await expect(user.getByText("请填写当前密码")).toBeVisible();
    await expect(user.getByText("请填写新密码")).toBeVisible();
    await expect(user.getByText("请再次输入新密码")).toBeVisible();
    await expect(user.getByRole("main").getByRole("status")).toHaveCount(0);

    // 全程没有修改：旧密码仍可登录，新密码不行
    const fresh = await newContext(browser, baseURL, page);
    await login(fresh, subject.accountA, NEW_PASSWORD);
    await expect(fresh.getByRole("alert").filter({ hasText: "账号名或密码错误" })).toBeVisible();
    await login(fresh, subject.accountA, OLD_PASSWORD);
    await expect(fresh).toHaveURL(/\/users$/);
  });

  test("连续输错当前密码：同一来源第 6 次（即使当前密码正确）被暂时拒绝，提示稍后再试，密码未改", async ({ page, browser, baseURL }) => {
    await login(page);
    await expect(page).toHaveURL(/\/users$/);
    const subject = await createSubject(page, "c");
    const user = await signIn(browser, baseURL, page, subject.accountA, OLD_PASSWORD);
    await user.goto("/account/password");

    for (let i = 0; i < 5; i++) {
      await fillForm(user, { current: `Wrong-Current-${i}x` });
      await submitAndSettle(user);
      await expect(field(user, "当前密码")).toHaveAccessibleDescription("当前密码不正确");
    }
    await fillForm(user, {});
    await submitAndSettle(user);
    await expect(formAlert(user).filter({ hasText: /尝试过于频繁，请 \d+ 秒后再试/ })).toBeVisible();
    await expect(user.getByRole("main").getByRole("status")).toHaveCount(0);

    // 密码没有被改：另一个来源仍能用旧密码登录
    const fresh = await signIn(browser, baseURL, page, subject.accountA, OLD_PASSWORD);
    await expect(fresh).toHaveURL(/\/users$/);
  });
});

test.describe("S9：修改成功", () => {
  test("提示「密码已修改」、当前会话保持有效、字段清空；耗时记录（SC-007）；新上下文里旧密码被拒、新密码可登录；同用户的另一个账号不受影响", async ({
    page,
    browser,
    baseURL,
  }, testInfo) => {
    await login(page);
    await expect(page).toHaveURL(/\/users$/);
    const subject = await createSubject(page, "d", true);
    const user = await signIn(browser, baseURL, page, subject.accountA, OLD_PASSWORD);

    // 从打开表单到看到成功提示（SC-007）
    const started = Date.now();
    await user.goto("/account/password");
    await fillForm(user, {});
    await submitAndSettle(user);
    await expect(user.getByRole("main").getByRole("status").filter({ hasText: "密码已修改" })).toBeVisible();
    const elapsedMs = Date.now() - started;
    testInfo.annotations.push({ type: "SC-007 改密耗时(ms)", description: String(elapsedMs) });
    expect(elapsedMs).toBeLessThanOrEqual(SC007_BUDGET_MS);

    // 字段清空，页面里没有任何密码；仍停留在改密页
    for (const label of ["当前密码", "新密码", "确认新密码"] as const) await expect(field(user, label)).toHaveValue("");
    await expect(user.locator("body")).not.toContainText(OLD_PASSWORD);
    await expect(user.locator("body")).not.toContainText(NEW_PASSWORD);
    await expect(user).toHaveURL(/\/account\/password$/);

    // 当前会话保持有效：刷新受保护页面仍是已登录
    await user.reload();
    await expect(user.getByRole("heading", { level: 1, name: "修改密码" })).toBeVisible();
    await user.goto("/users");
    await expect(user).toHaveURL(/\/users$/);
    await expect(user.getByRole("button", { name: new RegExp(subject.userName) })).toBeVisible();

    // 同一用户的另一个账号不受影响：仍用旧密码登录
    const other = await signIn(browser, baseURL, page, subject.accountB, OLD_PASSWORD);
    await expect(other.getByRole("button", { name: new RegExp(subject.userName) })).toBeVisible();

    // 最后验证（新登录会取代账号 A 上的旧会话）：旧密码被拒（统一提示），新密码可登录
    const fresh = await newContext(browser, baseURL, page);
    await login(fresh, subject.accountA, OLD_PASSWORD);
    await expect(fresh.getByRole("alert").filter({ hasText: "账号名或密码错误" })).toBeVisible();
    await expect(fresh).toHaveURL(/\/login/);
    await login(fresh, subject.accountA, NEW_PASSWORD);
    await expect(fresh).toHaveURL(/\/users$/);
    await expect(fresh.getByRole("button", { name: new RegExp(subject.userName) })).toBeVisible();
  });
});
