import AxeBuilder from "@axe-core/playwright";
import { type Browser, type BrowserContext, type Locator, type Page } from "@playwright/test";
import { ADMIN, expect, liveSessions, login, test, userIdOfAccount } from "./support/fixtures";
import { uamCli } from "./support/stack";

/**
 * US6：在 Session Manager 中强制下线某个会话（quickstart S8；FR-011、FR-016、FR-030；SC-006）。
 * 前提：栈已按 infra/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）；
 * UAM 已实现 US6（`DELETE /v1/sessions/{sessionRef}`，T129 ~ T131）。
 *
 * 造数：每个用例经「新建用户」界面（US4）建一个全新的用户及账号 A，需要时再用部署期命令 add-account 追加账号 B（密码只经标准输入）；
 * 名字带本次运行与视口的后缀，重复执行与六个视口项目互不冲突；名字刻意不含其它规格造数用户的子串
 * （多账号用户、无账号用户、已停用用户、分页用户、在线者、离线者、建档员、停权对象、甲号、乙号、丙号），避免串到它们的搜索与计数。
 * 被强制下线的目标会话只会是本用例刚造的用户的会话，绝不是 ADMIN 的。
 *
 * 「被下线者的登录页提示」：契约（verify 的 401 `session_invalid`）不区分「被管理员强制下线」与其它会话结束原因，
 * 所以被下线的浏览器看到的是通用提示「会话已结束，请重新登录。」（`reason=session_ended`），不是「你的会话已被管理员强制下线」；
 * 在契约增加可区分的原因之前，这里断言通用提示。
 *
 * 「任一实例」：栈目前只有一个 Admin Center 实例，只对这一个实例断言「下一次请求被视为未登录」；多实例在 US10 的 E2E 里补。
 */

const PASSWORD = "E2e-Terminate-Pass-1";
const runId = Date.now().toString(36);

const widthOf = (page: Page) => page.viewportSize()?.width ?? 1280;
const isNarrow = (page: Page) => widthOf(page) < 768;

interface Target {
  id: string;
  userName: string;
  /** 第一个账号（建用户时创建）。 */
  accountA: string;
  /** 第二个账号（`withSecondAccount` 才有）。 */
  accountB: string;
}

/** 以 ADMIN 身份经界面建一个全新的用户（账号 A）；`withSecondAccount` 时再追加账号 B。账号名 ≤ 32 个字符且只含 `[A-Za-z0-9_.-]`。 */
async function createTarget(page: Page, tag: string, withSecondAccount: boolean): Promise<Target> {
  const suffix = `${runId}${widthOf(page)}${tag}`;
  const userName = `强退对象${suffix}`;
  const accountA = `e2e.d6.${suffix}a`;
  const accountB = `e2e.d6.${suffix}b`;
  await page.goto("/users/new");
  await page.getByLabel("用户名", { exact: true }).fill(userName);
  await page.getByLabel("账号名", { exact: true }).fill(accountA);
  await page.getByLabel("初始密码", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: /^创建/ }).click();
  await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}\?created=1$/);
  const id = new URL(page.url()).pathname.split("/").pop()!;
  if (withSecondAccount) uamCli(["add-account", "--user-id", id, "--account-name", accountB], `${PASSWORD}\n`);
  return { id, userName, accountA, accountB };
}

const opened: BrowserContext[] = [];

/**
 * 新浏览器上下文不继承项目配置：baseURL、视口、来源地址头（x-forwarded-for）都显式带上，
 * 且每个上下文用不同的来源地址，避免登录失败限制串到其它用例。
 */
async function newContext(browser: Browser, baseURL: string | undefined, like: Page): Promise<{ context: BrowserContext; page: Page }> {
  const ip = `198.51.100.${20 + Math.floor(Math.random() * 200)}`;
  const context = await browser.newContext({
    baseURL,
    viewport: like.viewportSize() ?? undefined,
    extraHTTPHeaders: { "x-forwarded-for": ip },
  });
  opened.push(context);
  return { context, page: await context.newPage() };
}

async function loginAs(browser: Browser, baseURL: string | undefined, like: Page, account: string, password = PASSWORD) {
  const ctx = await newContext(browser, baseURL, like);
  await login(ctx.page, account, password);
  await expect(ctx.page).toHaveURL(/\/users$/);
  return ctx;
}

const userSessions = (userId: string) => liveSessions().filter((s) => s.userId === userId);

/** 在线用户列表里的每个用户：桌面是表格行，手机是卡片。 */
const onlineItems = (page: Page): Locator =>
  isNarrow(page)
    ? page.getByRole("list", { name: "在线用户卡片" }).getByRole("listitem")
    : page.getByRole("table", { name: "在线用户列表" }).locator("tbody tr");

const sessionCards = (page: Page) => page.getByRole("list", { name: "会话列表" }).getByRole("listitem");
const cardOf = (page: Page, account: string) => sessionCards(page).filter({ has: page.getByText(account, { exact: true }) });
const terminateButton = (card: Locator) => card.getByRole("button", { name: "强制下线" });

const seriousViolations = async (page: Page) =>
  (await new AxeBuilder({ page }).analyze()).violations.filter((v) => v.impact === "serious" || v.impact === "critical");

test.beforeEach(async ({ page }) => {
  await login(page);
  await expect(page).toHaveURL(/\/users$/);
});

test.afterEach(async () => {
  await Promise.all(opened.splice(0).map((context) => context.close()));
});

test.describe("S8：强制下线", () => {
  test("从在线用户列表出发 ≤ 4 次点击 / 触控（含确认）强制下线 A 的会话：A 下一次请求回登录页、B 不受影响、账号与用户状态不变、A 可再次登录（SC-006）", async ({
    page,
    browser,
    baseURL,
  }) => {
    const target = await createTarget(page, "a", true);
    const a = await loginAs(browser, baseURL, page, target.accountA);
    const b = await loginAs(browser, baseURL, page, target.accountB);
    expect(userSessions(target.id)).toHaveLength(2);

    // 从在线用户列表出发，数点击 / 触控：进入会话详情 → 点 A 的「强制下线」→ 点确认
    let clicks = 0;
    const click = async (locator: Locator) => {
      clicks += 1;
      await locator.click();
    };
    await page.goto("/sessions");
    await expect(onlineItems(page).filter({ hasText: target.userName })).toBeVisible();
    await click(onlineItems(page).filter({ hasText: target.userName }).getByRole("link"));
    await expect(page.getByRole("heading", { level: 1, name: `${target.userName} 的会话` })).toBeVisible();
    await expect(sessionCards(page)).toHaveCount(2);

    await click(terminateButton(cardOf(page, target.accountA)));
    const dialog = page.getByRole("alertdialog", { name: "强制下线该会话？" });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText(`账号「${target.accountA}」`);
    await click(dialog.getByRole("button", { name: "强制下线", exact: true }));
    await expect(dialog).toBeHidden();
    expect(clicks).toBeLessThanOrEqual(4);

    // 页面：成功提示；A 的卡片消失，B 的仍在
    await expect(page.getByRole("status").filter({ hasText: "会话已强制下线。" })).toBeVisible();
    await expect(cardOf(page, target.accountA)).toHaveCount(0);
    await expect(cardOf(page, target.accountB)).toHaveCount(1);
    await expect(sessionCards(page)).toHaveCount(1);

    // Redis：只剩一个会话（B 的）
    expect(userSessions(target.id)).toHaveLength(1);

    // A（被下线者）下一次请求被视为未登录：回登录页并提示会话已结束（通用提示，见文件头说明）
    await a.page.goto("/users");
    await expect(a.page).toHaveURL(/\/login\?.*reason=session_ended/);
    await expect(a.page.getByText("会话已结束，请重新登录。")).toBeVisible();
    await a.page.goto("/sessions");
    await expect(a.page).toHaveURL(/\/login/);

    // B（同一用户的另一个会话）不受影响
    await b.page.goto("/sessions");
    await expect(b.page).toHaveURL(/\/sessions$/);
    await expect(b.page.getByRole("heading", { level: 1, name: "会话管理" })).toBeVisible();

    // 账号与用户状态不变：用户仍是「正常」，两个账号都还在
    await page.goto(`/users/${target.id}`);
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("正常");
    await expect(page.getByText(target.accountA, { exact: true }).locator("visible=true")).toBeVisible();
    await expect(page.getByText(target.accountB, { exact: true }).locator("visible=true")).toBeVisible();

    // A 之后可以再次用同一账号登录（强制下线只终止会话）
    const again = await loginAs(browser, baseURL, page, target.accountA);
    await expect(again.page.getByRole("heading", { level: 1 })).toBeVisible();
    expect(userSessions(target.id)).toHaveLength(2);
  });

  test("运维人员自己的当前会话：入口禁用并提示改用「退出登录」，没有任何弹窗，会话仍有效", async ({ page }) => {
    await page.goto(`/sessions/${userIdOfAccount(ADMIN.account)}`);
    const own = sessionCards(page).filter({ hasText: "当前会话" });
    await expect(own).toHaveCount(1);
    const button = terminateButton(own);
    await expect(button).toBeDisabled();
    await expect(button).toHaveAccessibleDescription("这是你当前使用的会话，请改用顶栏用户菜单中的「退出登录」。");
    await expect(own.getByText("请改用顶栏用户菜单中的「退出登录」")).toBeVisible();
    await button.click({ force: true });
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    // 仍是登录状态
    await page.reload();
    await expect(sessionCards(page).filter({ hasText: "当前会话" })).toHaveCount(1);
  });

  test("对已结束的会话再强制下线：提示「该会话已结束」、不报错，并刷新为最新状态", async ({ page, browser, baseURL }) => {
    const target = await createTarget(page, "b", true);
    await loginAs(browser, baseURL, page, target.accountA);
    await loginAs(browser, baseURL, page, target.accountB);

    // 页面停在「A、B 两个会话都在」的旧状态；另一位运维（第二个 ADMIN 会话）先把 A 的会话下线
    await page.goto(`/sessions/${target.id}`);
    await expect(sessionCards(page)).toHaveCount(2);
    // 一个账号只有一个会话：第二位运维必须用另一个账号（挂在 ADMIN 用户下），否则登录会顶掉 page 自己的会话
    const operatorAccount = `e2e.d6.op${runId}${widthOf(page)}`;
    uamCli(["add-account", "--user-id", userIdOfAccount(ADMIN.account), "--account-name", operatorAccount], `${PASSWORD}\n`);
    const other = await loginAs(browser, baseURL, page, operatorAccount);
    await other.page.goto(`/sessions/${target.id}`);
    await terminateButton(cardOf(other.page, target.accountA)).click();
    await other.page.getByRole("alertdialog").getByRole("button", { name: "强制下线", exact: true }).click();
    await expect(other.page.getByRole("status").filter({ hasText: "会话已强制下线。" })).toBeVisible();
    expect(userSessions(target.id)).toHaveLength(1);

    // 旧页面上再点 A 的「强制下线」并确认：友好提示，不是错误
    await terminateButton(cardOf(page, target.accountA)).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "强制下线", exact: true }).click();
    await expect(page.getByRole("status").filter({ hasText: "该会话已结束" })).toBeVisible();
    await expect(page.getByRole("main").getByRole("alert")).toHaveCount(0);
    // 刷新为最新状态：A 的卡片没了，B 的还在
    await expect(cardOf(page, target.accountA)).toHaveCount(0);
    await expect(cardOf(page, target.accountB)).toHaveCount(1);
    expect(userSessions(target.id)).toHaveLength(1);
  });

  test("确认框：取消与 Esc 不下线、焦点回到入口；无整页横向滚动、axe 无严重违规；确认与取消的触控目标不小于 44px", async ({
    page,
    browser,
    baseURL,
  }) => {
    const target = await createTarget(page, "c", false);
    await loginAs(browser, baseURL, page, target.accountA);
    await page.goto(`/sessions/${target.id}`);
    const trigger = terminateButton(cardOf(page, target.accountA));
    expect((await trigger.boundingBox())!.height).toBeGreaterThanOrEqual(43.5);

    await trigger.click();
    const dialog = page.getByRole("alertdialog", { name: "强制下线该会话？" });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText("其他会话不受影响");
    await expect(dialog).toContainText("下一次操作会被带到登录页");
    // 打开动画（zoom-in）期间尺寸被缩放，量尺寸要等动画结束
    await dialog.evaluate((el) => Promise.all(el.getAnimations().map((animation) => animation.finished)));
    for (const name of ["取消", "强制下线"]) {
      expect((await dialog.getByRole("button", { name, exact: true }).boundingBox())!.height).toBeGreaterThanOrEqual(43.5);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
    expect(await seriousViolations(page)).toEqual([]);

    // 键盘：Tab 不会离开对话框；Esc 取消并把焦点还给入口
    await page.keyboard.press("Tab");
    await page.keyboard.press("Tab");
    await page.keyboard.press("Tab");
    expect(await page.evaluate(() => !!document.activeElement?.closest('[role="alertdialog"]'))).toBe(true);
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
    await expect(trigger).toBeFocused();
    expect(userSessions(target.id)).toHaveLength(1);

    // 点「取消」同样不下线
    await trigger.click();
    await page.getByRole("alertdialog").getByRole("button", { name: "取消", exact: true }).click();
    await expect(page.getByRole("alertdialog")).toBeHidden();
    await expect(cardOf(page, target.accountA)).toHaveCount(1);
    expect(userSessions(target.id)).toHaveLength(1);
  });
});
