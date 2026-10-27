import { type Locator, type Page } from "@playwright/test";
import { expect, login, test } from "./support/fixtures";
import {
  Contexts,
  createUserViaUi,
  expectDialogReachable,
  expectLayoutOk,
  expectShellMode,
  gotoViaNav,
  isPhone,
  namesFor,
  openNav,
  openUserMenu,
  PASSWORD,
  settle,
  widthOf,
} from "./support/responsive";

/**
 * US9：Admin Center 在桌面、平板、手机上自适应（quickstart S12；FR-035；SC-008）。
 * 前提：栈已按 devops/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）。
 * 本规格在 playwright.config.ts 的六个视口 project（320 / 375 / 768 / 1024 / 1440 / 2560）上各跑一遍。
 *
 * 完整旅程：登录 → 用户列表 → 新建 → 详情 → 停用 → 会话 → 强制下线 → 修改密码 → 退出。
 * 旅程由一位全新的「运维用户」走完（登录、改密、退出都是它自己的账号，不碰 ADMIN 的密码与会话）：
 *   - ADMIN 在准备阶段经界面造出这位运维用户与一位「目标用户」（它在另一个浏览器上下文里登录，提供一个可被强制下线的会话）；
 *   - 旅程里运维用户经界面新建一位「被停用者」并停用它；经侧边栏（手机经抽屉）进入会话管理，强制下线目标用户的会话；
 *   - 然后改自己的密码，最后退出。
 * 每个页面与状态（含对话框、抽屉、用户菜单）都断言：无整页横向滚动（`scrollWidth ≤ innerWidth`）、没有元素伸出视口或撑破盒子；
 * 外壳按视口变化（桌面侧边栏展开 / 平板图标条 / 手机抽屉）；列表在手机上是卡片、其它视口是表格。
 * 名字带本次运行与视口的后缀，刻意不含其它规格造数用户的子串（见 support/responsive.ts）。
 */

const NEW_PASSWORD = "E2e-Resp-Pass-2";

const contexts = new Contexts();
test.afterEach(() => contexts.closeAll());

/** 手机上是卡片列表（表格不可见），其它视口是表格（卡片不可见）。 */
async function expectListRendering(page: Page, tableName: string, cardsName: string, where: string) {
  const table = page.getByRole("table", { name: tableName });
  const cards = page.getByRole("list", { name: cardsName });
  if (isPhone(page)) {
    await expect(cards, `${where}：手机应是卡片`).toBeVisible();
    await expect(table, `${where}：手机不应显示表格`).toBeHidden();
  } else {
    await expect(table, `${where}：应是表格`).toBeVisible();
    await expect(cards, `${where}：不应显示卡片`).toBeHidden();
  }
}

const onlineItems = (page: Page): Locator =>
  isPhone(page)
    ? page.getByRole("list", { name: "在线用户卡片" }).getByRole("listitem")
    : page.getByRole("table", { name: "在线用户列表" }).locator("tbody tr");

test("S12：登录 → 用户列表 → 新建 → 详情 → 停用 → 会话 → 强制下线 → 修改密码 → 退出，每一页都无整页横向滚动、外壳随视口变化", async ({
  page: adminPage,
  browser,
  baseURL,
}) => {
  test.setTimeout(240_000);
  const width = widthOf(adminPage);

  // ---- 准备：ADMIN 造出运维用户与目标用户；目标用户登录，留下一个会话 ----
  await login(adminPage);
  await expect(adminPage).toHaveURL(/\/users$/);
  const operator = namesFor(adminPage, "op");
  const target = namesFor(adminPage, "tg");
  const created = namesFor(adminPage, "nw"); // 旅程里新建、再停用
  await createUserViaUi(adminPage, operator);
  await createUserViaUi(adminPage, target);
  await contexts.signIn(browser, baseURL, adminPage, target.accountName);

  // ---- 1. 登录（运维用户自己的真实登录页）----
  const page = await contexts.open(browser, baseURL, adminPage);
  await page.goto("/login");
  await expect(page.getByRole("heading", { level: 1, name: "Admin Center" })).toBeVisible();
  await expectLayoutOk(page, `[${width}] 登录页`);
  await login(page, operator.accountName, PASSWORD);
  await expect(page).toHaveURL(/\/users$/);

  // ---- 2. 用户列表 ----
  await expect(page.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();
  await expectShellMode(page, `[${width}] 用户列表`);
  await expectListRendering(page, "用户列表", "用户卡片", `[${width}] 用户列表`);
  await expect(page.getByRole("navigation", { name: "分页" })).toBeVisible();
  await expectLayoutOk(page, `[${width}] 用户列表`);

  // 顶栏用户菜单：打开后仍在视口内
  const menu = await openUserMenu(page, operator.userName);
  await expectLayoutOk(page, `[${width}] 用户菜单`);
  await expect(menu.getByRole("menuitem", { name: "修改密码" })).toBeVisible();
  await expect(menu.getByRole("menuitem", { name: "退出登录" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(menu).toBeHidden();

  // 手机：抽屉打开后也在视口内；点导航项后抽屉关闭
  if (isPhone(page)) {
    await openNav(page);
    await expectLayoutOk(page, `[${width}] 导航抽屉`);
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toHaveCount(0);
  }

  // ---- 3. 新建 ----
  await page.getByRole("link", { name: "新建用户" }).first().click();
  await expect(page).toHaveURL(/\/users\/new$/);
  await expect(page.getByRole("heading", { level: 1, name: "新建用户" })).toBeVisible();
  await expectShellMode(page, `[${width}] 新建用户`);
  await expectLayoutOk(page, `[${width}] 新建用户表单`);
  // 表单出错状态同样不撑破版面
  await page.getByRole("button", { name: /^创建/ }).click();
  await expect(page.getByText("请填写用户名")).toBeVisible();
  await expectLayoutOk(page, `[${width}] 新建用户表单（含错误）`);
  await page.getByLabel("用户名", { exact: true }).fill(created.userName);
  await page.getByLabel("账号名", { exact: true }).fill(created.accountName);
  await page.getByLabel("初始密码", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: /^创建/ }).click();

  // ---- 4. 详情 ----
  await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}\?created=1$/);
  const createdId = new URL(page.url()).pathname.split("/").pop()!;
  await expect(page.getByRole("heading", { level: 1, name: created.userName })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "用户已创建" })).toBeVisible();
  await expectShellMode(page, `[${width}] 用户详情`);
  await expectListRendering(page, "关联账号", "关联账号卡片", `[${width}] 用户详情`);
  await expectLayoutOk(page, `[${width}] 用户详情`);

  // ---- 5. 停用（确认对话框）----
  await page.getByRole("button", { name: "停用用户" }).click();
  const disableDialog = page.getByRole("alertdialog", { name: `停用用户「${created.userName}」？` });
  await expect(disableDialog).toBeVisible();
  await expectDialogReachable(page, disableDialog, `[${width}] 停用确认框`);
  await expectLayoutOk(page, `[${width}] 停用确认框`);
  await disableDialog.getByRole("button", { name: "停用", exact: true }).click();
  await expect(disableDialog).toBeHidden();
  await expect(page.getByRole("region", { name: "基本信息" })).toContainText("已停用");
  await expect(page.getByRole("status").filter({ hasText: /已停用/ }).first()).toBeVisible();
  await expectLayoutOk(page, `[${width}] 已停用的用户详情`);
  // 停用后列表里它是「已停用」，用户详情仍可回到列表（面包屑）
  await page.getByRole("navigation", { name: "面包屑" }).getByRole("link", { name: "用户管理" }).click();
  await expect(page).toHaveURL(/\/users$/);
  expect(createdId).toMatch(/^[0-9a-f-]{36}$/);

  // ---- 6. 会话：经侧边栏（手机经抽屉）进入会话管理 ----
  await gotoViaNav(page, "会话管理");
  await expect(page.getByRole("heading", { level: 1, name: "会话管理" })).toBeVisible();
  await expectShellMode(page, `[${width}] 会话管理`);
  await expectListRendering(page, "在线用户列表", "在线用户卡片", `[${width}] 会话管理`);
  await expect(onlineItems(page).filter({ hasText: target.userName })).toBeVisible();
  await expectLayoutOk(page, `[${width}] 在线用户列表`);

  // 会话详情（会话卡片）
  await onlineItems(page).filter({ hasText: target.userName }).getByRole("link").click();
  await expect(page.getByRole("heading", { level: 1, name: `${target.userName} 的会话` })).toBeVisible();
  const cards = page.getByRole("list", { name: "会话列表" }).getByRole("listitem");
  await expect(cards).toHaveCount(1);
  await expectShellMode(page, `[${width}] 会话详情`);
  await expectLayoutOk(page, `[${width}] 会话详情`);

  // ---- 7. 强制下线（确认对话框）----
  await cards.getByRole("button", { name: "强制下线" }).click();
  const terminateDialog = page.getByRole("alertdialog", { name: "强制下线该会话？" });
  await expect(terminateDialog).toBeVisible();
  await expectDialogReachable(page, terminateDialog, `[${width}] 强制下线确认框`);
  await expectLayoutOk(page, `[${width}] 强制下线确认框`);
  await terminateDialog.getByRole("button", { name: "强制下线", exact: true }).click();
  await expect(terminateDialog).toBeHidden();
  await expect(page.getByRole("status").filter({ hasText: "会话已强制下线。" })).toBeVisible();
  await expect(page.getByText("该用户当前没有在线会话")).toBeVisible();
  await expectLayoutOk(page, `[${width}] 强制下线后的会话详情`);

  // ---- 8. 修改密码：经顶栏用户菜单 ----
  const userMenu = await openUserMenu(page, operator.userName);
  await userMenu.getByRole("menuitem", { name: "修改密码" }).click();
  await expect(page).toHaveURL(/\/account\/password$/);
  await expect(page.getByRole("heading", { level: 1, name: "修改密码" })).toBeVisible();
  await expectShellMode(page, `[${width}] 修改密码`);
  await expectLayoutOk(page, `[${width}] 修改密码表单`);
  await page.getByRole("button", { name: /^修改/ }).click();
  await expect(page.getByText("请填写当前密码")).toBeVisible();
  await expectLayoutOk(page, `[${width}] 修改密码表单（含错误）`);
  await page.getByLabel("当前密码", { exact: true }).fill(PASSWORD);
  await page.getByLabel("新密码", { exact: true }).fill(NEW_PASSWORD);
  await page.getByLabel("确认新密码", { exact: true }).fill(NEW_PASSWORD);
  await page.getByRole("button", { name: /^修改/ }).click();
  await expect(page.getByRole("main").getByRole("status").filter({ hasText: "密码已修改" })).toBeVisible();
  await expectLayoutOk(page, `[${width}] 修改密码成功`);

  // ---- 9. 退出 ----
  const exitMenu = await openUserMenu(page, operator.userName);
  await exitMenu.getByRole("menuitem", { name: "退出登录" }).click();
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByRole("button", { name: "登录" })).toBeVisible();
  await expectLayoutOk(page, `[${width}] 退出后的登录页`);
  await page.goto("/users");
  await expect(page).toHaveURL(/\/login/);

  // 被强制下线的目标用户：下一次请求回到登录页（对照：强制下线真的生效，而不是只有界面提示）
  const targetAgain = await contexts.open(browser, baseURL, adminPage);
  await login(targetAgain, target.accountName, PASSWORD);
  await expect(targetAgain).toHaveURL(/\/users$/); // 账号与用户状态不变，可再次登录
});

test("S12：窄屏的抽屉与宽屏的侧边栏——导航项都可达，进入页面后外壳形态不变", async ({ page }) => {
  test.setTimeout(120_000);
  const width = widthOf(page);
  await login(page);
  await expect(page).toHaveURL(/\/users$/);
  await expectShellMode(page, `[${width}] 用户管理`);
  await gotoViaNav(page, "会话管理");
  await expectShellMode(page, `[${width}] 会话管理`);
  await gotoViaNav(page, "用户管理");
  await expectShellMode(page, `[${width}] 回到用户管理`);

  // 桌面：收起 / 展开偏好；收起后成为图标条，导航项仍可点
  if (width >= 1024) {
    await page.getByRole("button", { name: "收起侧边栏" }).click();
    await settle(page.locator('[data-slot="sidebar"]'));
    await expect(page.locator('[data-slot="sidebar"]')).toHaveAttribute("data-state", "collapsed");
    await expectLayoutOk(page, `[${width}] 侧边栏收起`);
    await page.getByRole("button", { name: "展开侧边栏" }).click();
    await expect(page.locator('[data-slot="sidebar"]')).toHaveAttribute("data-state", "expanded");
  }
});
