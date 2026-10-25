import AxeBuilder from "@axe-core/playwright";
import { type Page } from "@playwright/test";
import { accountCountNamed, expect, login, SOURCE_HEADERS, test, userCountNamed } from "./support/fixtures";

/**
 * US4：在 User Manager 中新建「账号名 / 密码」用户（quickstart S5；FR-021 ~ FR-023；SC-004）。
 * 前提：栈已按 infra/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）；UAM 已实现 `POST /v1/users`。
 * 每次运行、每个视口项目用独立的名字（后缀），重复执行与六个视口项目互不冲突；
 * 名字刻意不含其它规格造数用户的子串（多账号用户、无账号用户、已停用用户、分页用户、在线者、离线者），避免串到它们的搜索与计数。
 * 「并发抢同一账号名」属于 UAM 的确定性并发测试（T098），不在浏览器里测。
 */

const SC004_BUDGET_MS = 120_000;
const PASSWORD = "E2e-Create-Pass-1";
const runId = Date.now().toString(36);

/** 本用例专用的一组名字：账号名 ≤ 32 个字符且只含 `[A-Za-z0-9_.-]`。 */
function names(width: number, tag: string) {
  const suffix = `${runId}${width}${tag}`;
  return { userName: `建档员${suffix}`, accountName: `e2e.c4.${suffix}` };
}

const widthOf = (page: Page) => page.viewportSize()?.width ?? 1280;

async function fill(page: Page, v: { userName: string; accountName: string; password?: string }) {
  await page.getByLabel("用户名", { exact: true }).fill(v.userName);
  await page.getByLabel("账号名", { exact: true }).fill(v.accountName);
  await page.getByLabel("初始密码", { exact: true }).fill(v.password ?? PASSWORD);
}

const submit = (page: Page) => page.getByRole("button", { name: /^创建/ });

const seriousViolations = async (page: Page) =>
  (await new AxeBuilder({ page }).analyze()).violations.filter((v) => v.impact === "serious" || v.impact === "critical");

test.beforeEach(async ({ page }) => {
  await login(page);
  await expect(page).toHaveURL(/\/users$/);
});

test.describe("S5：新建用户表单", () => {
  test("列表页「新建用户」入口进入表单；字段、说明、autocomplete；无整页横向滚动；axe 无严重违规；触控目标不小于 44px", async ({ page }) => {
    await page.getByRole("link", { name: "新建用户" }).first().click();
    await expect(page).toHaveURL(/\/users\/new$/);
    await expect(page.getByRole("heading", { level: 1, name: "新建用户" })).toBeVisible();
    await expect(page.getByLabel("账号名", { exact: true })).toHaveAttribute("autocomplete", "off");
    await expect(page.getByLabel("初始密码", { exact: true })).toHaveAttribute("autocomplete", "new-password");
    await expect(page.getByLabel("初始密码", { exact: true })).toHaveAttribute("type", "password");
    await expect(page.getByText("至少 10 位，且同时包含字母和数字。")).toBeVisible();

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    expect(await seriousViolations(page)).toEqual([]);
    for (const control of [
      page.getByLabel("用户名", { exact: true }),
      page.getByLabel("账号名", { exact: true }),
      page.getByLabel("初始密码", { exact: true }),
      submit(page),
      page.getByRole("link", { name: "取消" }),
    ]) {
      expect((await control.boundingBox())!.height).toBeGreaterThanOrEqual(43.5);
    }
  });

  test("必填为空：字段旁给出原因，不创建；取消回到列表", async ({ page }) => {
    await page.goto("/users/new");
    await submit(page).click();
    await expect(page.getByText("请填写用户名")).toBeVisible();
    await expect(page.getByText("请填写账号名")).toBeVisible();
    await expect(page.getByText("请填写初始密码")).toBeVisible();
    await expect(page).toHaveURL(/\/users\/new$/);

    await page.getByRole("link", { name: "取消" }).click();
    await expect(page).toHaveURL(/\/users$/);
  });
});

test.describe("S5：创建并登录", () => {
  test("创建成功进入详情页并提示「用户已创建」；新账号立即可登录（新浏览器上下文）；记录创建耗时（SC-004）", async ({ page, browser, baseURL }, testInfo) => {
    const v = names(widthOf(page), "a");
    const started = Date.now();
    await page.goto("/users/new");
    await fill(page, v);
    await submit(page).click();

    await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}\?created=1$/);
    await expect(page.getByRole("status").filter({ hasText: "用户已创建" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 1, name: v.userName })).toBeVisible();
    const elapsedMs = Date.now() - started;
    testInfo.annotations.push({ type: "SC-004 创建耗时(ms)", description: String(elapsedMs) });
    expect(elapsedMs).toBeLessThanOrEqual(SC004_BUDGET_MS);

    // 详情：状态「正常」，恰好一个账号，且页面里没有密码
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("正常");
    await expect(page.getByRole("heading", { level: 2, name: "关联账号（1）" })).toBeVisible();
    await expect(page.getByText(v.accountName, { exact: true }).locator("visible=true")).toBeVisible();
    await expect(page.locator("body")).not.toContainText(PASSWORD);
    expect(userCountNamed(v.userName)).toBe(1);
    expect(accountCountNamed(v.accountName)).toBe(1);

    // 新账号立即可登录（新上下文不继承项目配置：baseURL、视口、来源地址头都要显式带上）
    const context = await browser.newContext({
      baseURL,
      viewport: page.viewportSize() ?? undefined,
      extraHTTPHeaders: SOURCE_HEADERS,
    });
    try {
      const fresh = await context.newPage();
      await login(fresh, v.accountName, PASSWORD);
      await expect(fresh).toHaveURL(/\/users$/);
      await expect(fresh.getByRole("button", { name: new RegExp(v.userName) })).toBeVisible();
    } finally {
      await context.close();
    }
  });
});

test.describe("S5：被拒的创建", () => {
  test("账号名只有大小写不同：在账号名字段旁提示「账号名已被使用」，保留已填内容（密码除外），不建数据；改名后可重试成功", async ({ page }) => {
    const first = names(widthOf(page), "b");
    await page.goto("/users/new");
    await fill(page, first);
    await submit(page).click();
    await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}\?created=1$/);

    const second = { userName: `${first.userName}重名`, accountName: first.accountName.toUpperCase() };
    await page.goto("/users/new");
    await fill(page, second);
    await submit(page).click();

    const account = page.getByLabel("账号名", { exact: true });
    await expect(account).toHaveAttribute("aria-invalid", "true");
    await expect(account).toHaveAccessibleDescription("账号名已被使用");
    await expect(page).toHaveURL(/\/users\/new$/);
    await expect(page.getByLabel("用户名", { exact: true })).toHaveValue(second.userName);
    await expect(account).toHaveValue(second.accountName);
    await expect(page.getByLabel("初始密码", { exact: true })).toHaveValue("");
    await expect(page.locator("body")).not.toContainText(PASSWORD);
    expect(userCountNamed(second.userName)).toBe(0);
    expect(accountCountNamed(first.accountName)).toBe(1);

    // 改成可用的账号名后重试（幂等键已更换，不会被重放成上一次的拒绝）
    const renamed = names(widthOf(page), "c").accountName;
    await account.fill(renamed);
    await page.getByLabel("初始密码", { exact: true }).fill(PASSWORD);
    await submit(page).click();
    await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}\?created=1$/);
    expect(userCountNamed(second.userName)).toBe(1);
  });

  test("弱密码与不合规的账号名：字段旁给出具体原因，不创建任何数据", async ({ page }) => {
    const v = names(widthOf(page), "d");
    await page.goto("/users/new");
    await fill(page, { ...v, accountName: "a b", password: "weak1" });
    await submit(page).click();

    await expect(page.getByLabel("初始密码", { exact: true })).toHaveAccessibleDescription("密码至少 10 位，且同时包含字母和数字");
    await expect(page.getByLabel("账号名", { exact: true })).toHaveAccessibleDescription("账号名为 3–32 个字母、数字或 _ . -");
    await expect(page.getByLabel("用户名", { exact: true })).toHaveValue(v.userName);
    await expect(page.getByLabel("初始密码", { exact: true })).toHaveValue("");

    // 长度够但缺数字
    await page.getByLabel("账号名", { exact: true }).fill(v.accountName);
    await page.getByLabel("初始密码", { exact: true }).fill("onlyletterspassword");
    await submit(page).click();
    await expect(page.getByLabel("初始密码", { exact: true })).toHaveAccessibleDescription("密码至少 10 位，且同时包含字母和数字");
    await expect(page).toHaveURL(/\/users\/new$/);
    expect(userCountNamed(v.userName)).toBe(0);
    expect(accountCountNamed(v.accountName)).toBe(0);
  });
});

test.describe("S5：重复提交", () => {
  test("双击「创建用户」只建一个用户", async ({ page }) => {
    const v = names(widthOf(page), "e");
    await page.goto("/users/new");
    await fill(page, v);
    await submit(page).dblclick();

    await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}\?created=1$/);
    await expect(page.getByRole("heading", { level: 1, name: v.userName })).toBeVisible();
    expect(userCountNamed(v.userName)).toBe(1);
    expect(accountCountNamed(v.accountName)).toBe(1);
  });
});
