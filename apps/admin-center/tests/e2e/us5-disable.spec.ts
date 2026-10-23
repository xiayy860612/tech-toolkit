import AxeBuilder from "@axe-core/playwright";
import { type Browser, type BrowserContext, type Page } from "@playwright/test";
import { ADMIN, expect, liveSessions, login, test, userIdOfAccount } from "./support/fixtures";

/**
 * US5：在 User Manager 中停用与启用用户（quickstart S6；FR-016、FR-024、FR-025；SC-005）。
 * 前提：栈已按 infra/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）；UAM 已实现 US5
 * （`POST /v1/users/{id}/disable|enable`）。
 *
 * 造数：每个用例经「新建用户」界面（US4）建一个全新的用户及账号，名字带本次运行与视口的后缀，重复执行与六个视口项目互不冲突；
 * 名字刻意不含其它规格造数用户的子串（多账号用户、无账号用户、已停用用户、分页用户、在线者、离线者、建档员），避免串到它们的搜索与计数。
 * 被停用的目标用户只会是本用例刚建的用户，绝不是 ADMIN。
 *
 * 「任一实例」：栈目前只有一个 Admin Center 实例，这里只对这一个实例断言「下一次请求被视为未登录」；
 * 多实例（US10）的同一断言在 T163 及以后补上（会话只存在 UAM / Redis 里，实例无状态，所以逻辑相同）。
 * 「最后一个正常用户」在共享的栈上无法安全造出（要先停用所有其他用户，会破坏其它用例），
 * 只在单元测试（status-actions / user-status-actions）与 UAM 的并发测试里覆盖，这里不测。
 */

const PASSWORD = "E2e-Disable-Pass-1";
const runId = Date.now().toString(36);

const widthOf = (page: Page) => page.viewportSize()?.width ?? 1280;

interface Target {
  id: string;
  userName: string;
  accountName: string;
}

/** 以 ADMIN 身份经界面建一个全新的用户；账号名 ≤ 32 个字符且只含 `[A-Za-z0-9_.-]`。 */
async function createTarget(page: Page, tag: string): Promise<Target> {
  const suffix = `${runId}${widthOf(page)}${tag}`;
  const userName = `停权对象${suffix}`;
  const accountName = `e2e.d5.${suffix}`;
  await page.goto("/users/new");
  await page.getByLabel("用户名", { exact: true }).fill(userName);
  await page.getByLabel("账号名", { exact: true }).fill(accountName);
  await page.getByLabel("初始密码", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: /^创建/ }).click();
  await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}\?created=1$/);
  const id = new URL(page.url()).pathname.split("/").pop()!;
  return { id, userName, accountName };
}

const opened: BrowserContext[] = [];

/**
 * 在新的浏览器上下文里以某个账号登录。新上下文不继承项目配置：baseURL、视口都要显式带上；
 * 来源地址头（x-forwarded-for）也显式传入，且每个上下文用不同的地址，避免登录失败限制串到其它用例。
 */
async function newContext(browser: Browser, baseURL: string | undefined, like: Page, ip: string): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({
    baseURL,
    viewport: like.viewportSize() ?? undefined,
    extraHTTPHeaders: { "x-forwarded-for": ip },
  });
  opened.push(context);
  return { context, page: await context.newPage() };
}

const randomIp = () => `198.51.100.${20 + Math.floor(Math.random() * 200)}`;

async function loginInNewContext(browser: Browser, baseURL: string | undefined, like: Page, target: Target) {
  const ctx = await newContext(browser, baseURL, like, randomIp());
  await login(ctx.page, target.accountName, PASSWORD);
  await expect(ctx.page).toHaveURL(/\/users$/);
  return ctx;
}

const userSessionCount = (userId: string) => liveSessions().filter((s) => s.userId === userId).length;

const disableButton = (page: Page) => page.getByRole("button", { name: "停用用户" });
const enableButton = (page: Page) => page.getByRole("button", { name: "启用用户" });

/** 停用 / 启用都要经过确认框：点入口 → 看到说明 → 点确认。 */
async function confirmDialog(page: Page, trigger: "停用用户" | "启用用户", confirm: "停用" | "启用") {
  await page.getByRole("button", { name: trigger }).click();
  const dialog = page.getByRole("alertdialog");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: confirm, exact: true }).click();
  await expect(dialog).toBeHidden();
}

const seriousViolations = async (page: Page) =>
  (await new AxeBuilder({ page }).analyze()).violations.filter((v) => v.impact === "serious" || v.impact === "critical");

test.beforeEach(async ({ page }) => {
  await login(page);
  await expect(page).toHaveURL(/\/users$/);
});

test.afterEach(async () => {
  await Promise.all(opened.splice(0).map((context) => context.close()));
});

test.describe("S6：停用与启用", () => {
  test("停用：会话立即结束、该用户下一次请求回登录页、登录被拒（统一提示）、从在线列表消失；启用后可重新登录，旧会话不恢复（SC-005）", async ({
    page,
    browser,
    baseURL,
  }) => {
    const target = await createTarget(page, "a");
    const victim = await loginInNewContext(browser, baseURL, page, target);
    expect(userSessionCount(target.id)).toBe(1);
    // 停用前，运维在该用户的会话页能看到这条在线会话
    await page.goto(`/sessions/${target.id}`);
    await expect(page.getByRole("heading", { level: 1, name: `${target.userName} 的会话` })).toBeVisible();

    // 停用
    await page.goto(`/users/${target.id}`);
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("正常");
    await confirmDialog(page, "停用用户", "停用");
    await expect(page.getByRole("status").filter({ hasText: `用户「${target.userName}」已停用，其会话已结束。` })).toBeVisible();
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("已停用");
    await expect(enableButton(page)).toBeVisible();
    await expect(disableButton(page)).toHaveCount(0);

    // 会话立即结束：Redis 里没有该用户的有效会话；该用户（原浏览器上下文）下一次请求被视为未登录，回登录页并提示会话已结束
    expect(userSessionCount(target.id)).toBe(0);
    await victim.page.goto("/users");
    await expect(victim.page).toHaveURL(/\/login\?.*reason=session_ended/);
    await expect(victim.page.getByText("会话已结束，请重新登录。")).toBeVisible();
    await victim.page.goto("/sessions");
    await expect(victim.page).toHaveURL(/\/login/);

    // 登录被拒，提示与错密码一致，不透露「已停用」（换一个来源地址，避免被登录失败限制干扰）
    const retry = await newContext(browser, baseURL, page, randomIp());
    await login(retry.page, target.accountName, PASSWORD);
    await expect(retry.page).toHaveURL(/\/login/);
    const refused = retry.page.getByRole("alert").filter({ hasText: "账号名或密码错误" });
    await expect(refused).toBeVisible();
    expect(await refused.innerText()).not.toMatch(/停用|禁用|已停/);

    // 从在线用户中消失：该用户的会话页是空态，且 Redis 里没有会话
    await page.goto(`/sessions/${target.id}`);
    await expect(page.getByText("该用户当前没有在线会话")).toBeVisible();
    expect(userSessionCount(target.id)).toBe(0);

    // 启用
    await page.goto(`/users/${target.id}`);
    await confirmDialog(page, "启用用户", "启用");
    await expect(page.getByRole("status").filter({ hasText: `用户「${target.userName}」已启用。` })).toBeVisible();
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("正常");
    await expect(disableButton(page)).toBeVisible();

    // 旧会话不会因启用而复活
    expect(userSessionCount(target.id)).toBe(0);
    await victim.page.goto("/users");
    await expect(victim.page).toHaveURL(/\/login/);

    // 启用后可重新登录
    const again = await newContext(browser, baseURL, page, randomIp());
    await login(again.page, target.accountName, PASSWORD);
    await expect(again.page).toHaveURL(/\/users$/);
    expect(userSessionCount(target.id)).toBe(1);
  });

  test("停用自己：入口禁用并说明原因，且没有任何弹窗", async ({ page }) => {
    await page.goto(`/users/${userIdOfAccount(ADMIN.account)}`);
    const button = disableButton(page);
    await expect(button).toBeDisabled();
    await expect(button).toHaveAccessibleDescription("不能停用当前登录的用户。");
    await expect(page.getByText("不能停用当前登录的用户。")).toBeVisible();
    await button.click({ force: true });
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    // 仍然是登录状态、仍然是正常用户
    await page.reload();
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("正常");
  });

  test("确认框：说明后果；取消不停用、焦点回到入口；无整页横向滚动、axe 无严重违规；确认与取消的触控目标不小于 44px", async ({ page }) => {
    const target = await createTarget(page, "b");
    await page.goto(`/users/${target.id}`);
    const trigger = disableButton(page);
    expect((await trigger.boundingBox())!.height).toBeGreaterThanOrEqual(43.5);

    await trigger.click();
    const dialog = page.getByRole("alertdialog", { name: `停用用户「${target.userName}」？` });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText("所有账号都无法登录");
    await expect(dialog).toContainText("所有在线会话会立即结束");
    await expect(dialog).toContainText("已结束的会话不会恢复");
    // 打开动画（zoom-in）期间尺寸被缩放，量尺寸要等动画结束
    await dialog.evaluate((el) => Promise.all(el.getAnimations().map((a) => a.finished)));
    for (const name of ["取消", "停用"]) {
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
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("正常");

    // 点「取消」同样不改变状态
    await trigger.click();
    await page.getByRole("alertdialog").getByRole("button", { name: "取消", exact: true }).click();
    await expect(page.getByRole("alertdialog")).toBeHidden();
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("正常");
  });
});
