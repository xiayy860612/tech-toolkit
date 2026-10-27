import { type Locator, type Page } from "@playwright/test";
import { expect, login, test } from "./support/fixtures";
import {
  Contexts,
  createUserViaUi,
  expectDialogReachable,
  expectLayoutOk,
  isPhone,
  minGapToNeighbours,
  namesFor,
  openNav,
  openUserMenu,
  PASSWORD,
  runId,
  settle,
  touchTargetProblems,
  widthOf,
} from "./support/responsive";

/**
 * US9：触控目标与内容适应（quickstart S12；FR-035、FR-036）。
 * 前提：栈已按 devops/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）。在六个视口 project 上各跑一遍。
 *
 * - 触控目标：每个可交互元素的有效目标 ≥ 44 × 44 CSS 像素（度量方法见 support/responsive.ts 的 touchTargetProblems：盒子 ≥ 44 直接通过，
 *   否则在盒子外 44px 内做命中测试，取真正命中该元素的区域，所以手机上「整张卡片可点」的拉伸链接按整卡算）。
 *   **没有豁免**：WCAG 2.5.8 对「行文中的链接」的例外在本产品里用不上——页面上没有嵌在句子里的链接；
 *   「跳到主要内容」平时是 sr-only，获得焦点才显示，所以在获得焦点后单独量。
 * - 破坏性按钮（停用用户、强制下线及其确认键）与相邻的可交互元素留 ≥ 8px 间距。
 * - 很长的名字（64 个字符的用户名：一整串不可断的宽字符、64 个汉字；32 个字符的账号名）在各视口换行或截断，
 *   不撑破版面：无整页横向滚动、没有元素伸出视口、没有内容撑破自己的盒子；确认框里的长名字也不撑破对话框。
 * - 矮视口（320 高，横屏手机）：确认框落在视口内并可滚动，取消与确认仍可点。
 * 名字带本次运行与视口的后缀，刻意不含其它规格造数用户的子串（见 support/responsive.ts）。
 */

const contexts = new Contexts();
test.afterEach(() => contexts.closeAll());

async function loginAdmin(page: Page) {
  await login(page);
  await expect(page).toHaveURL(/\/users$/);
}

/** 断言一页里没有触控目标不足 44 × 44 的可交互元素。 */
async function expectTouchTargets(page: Page, where: string, root = "body") {
  expect(await touchTargetProblems(page, root), `${where}：触控目标不足 44 × 44`).toEqual([]);
}

const online = (page: Page, text: string): Locator =>
  isPhone(page)
    ? page.getByRole("list", { name: "在线用户卡片" }).getByRole("listitem").filter({ hasText: text })
    : page.getByRole("table", { name: "在线用户列表" }).locator("tbody tr").filter({ hasText: text });

test.describe("触控目标 ≥ 44 × 44", () => {
  test("登录页、用户列表（含搜索与分页）、新建表单、用户详情、会话列表与会话卡片、修改密码", async ({ page, browser, baseURL }) => {
    const width = widthOf(page);
    // 登录页（未登录）
    await page.goto("/login");
    await expect(page.getByRole("button", { name: "登录" })).toBeVisible();
    await expectTouchTargets(page, `[${width}] 登录页`);

    await loginAdmin(page);
    const subject = namesFor(page, "tt");
    const id = await createUserViaUi(page, subject);
    await contexts.signIn(browser, baseURL, page, subject.accountName);

    // 「跳到主要内容」获得焦点后才显示：Tab 一次，量它
    await page.goto("/users");
    await page.keyboard.press("Tab");
    const skip = page.getByRole("link", { name: "跳到主要内容" });
    await expect(skip).toBeFocused();
    const skipBox = (await skip.boundingBox())!;
    expect(skipBox.height, `[${width}] 「跳到主要内容」高度`).toBeGreaterThanOrEqual(43.5);
    expect(skipBox.width, `[${width}] 「跳到主要内容」宽度`).toBeGreaterThanOrEqual(43.5);

    await expect(page.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();
    await expectTouchTargets(page, `[${width}] 用户列表`);

    // 搜索出一个只有一条结果的列表（短名字不会因为文字短而目标过小：下面单独造一个单字用户）
    await page.goto(`/users?q=${encodeURIComponent(subject.userName)}`);
    await expect(page.getByRole("link", { name: subject.userName }).locator("visible=true")).toBeVisible();
    await expectTouchTargets(page, `[${width}] 搜索结果`);

    await page.goto("/users/new");
    await expect(page.getByRole("heading", { level: 1, name: "新建用户" })).toBeVisible();
    await expectTouchTargets(page, `[${width}] 新建用户表单`);

    await page.goto(`/users/${id}`);
    await expect(page.getByRole("heading", { level: 1, name: subject.userName })).toBeVisible();
    await expectTouchTargets(page, `[${width}] 用户详情`);

    await page.goto("/sessions");
    await expect(online(page, subject.userName)).toBeVisible();
    await expectTouchTargets(page, `[${width}] 在线用户列表`);

    await page.goto(`/sessions/${id}`);
    await expect(page.getByRole("list", { name: "会话列表" }).getByRole("listitem")).toHaveCount(1);
    await expectTouchTargets(page, `[${width}] 会话卡片`);

    await page.goto("/account/password");
    await expect(page.getByRole("heading", { level: 1, name: "修改密码" })).toBeVisible();
    await expectTouchTargets(page, `[${width}] 修改密码表单`);
  });

  test("很短的名字（单个字）的列表链接：目标仍 ≥ 44 × 44", async ({ page }) => {
    const width = widthOf(page);
    await loginAdmin(page);
    // 单字用户名：链接文字只有一个字宽
    const name = String.fromCodePoint(0x4e00 + (parseInt(runId, 36) % 20000));
    await createUserViaUi(page, { userName: name, accountName: `e2e.r9.s${runId}${width}` });
    await page.goto(`/users?q=${encodeURIComponent(name)}`);
    await expect(page.getByRole("link", { name, exact: true }).locator("visible=true").first()).toBeVisible();
    await expectTouchTargets(page, `[${width}] 单字用户名的搜索结果`);
  });

  test("外壳里的浮层：用户菜单、手机的导航抽屉、停用 / 启用 / 强制下线确认框", async ({ page, browser, baseURL }) => {
    const width = widthOf(page);
    await loginAdmin(page);
    const subject = namesFor(page, "tf");
    const id = await createUserViaUi(page, subject);
    await contexts.signIn(browser, baseURL, page, subject.accountName);

    await page.goto("/users");
    await openUserMenu(page, "系统管理员");
    await expectTouchTargets(page, `[${width}] 用户菜单`, '[role="menu"]');
    await page.keyboard.press("Escape");

    if (isPhone(page)) {
      await openNav(page);
      await expectTouchTargets(page, `[${width}] 导航抽屉`, '[role="dialog"]');
      await page.keyboard.press("Escape");
    } else {
      await expectTouchTargets(page, `[${width}] 侧边栏`, 'nav[aria-label="主导航"]');
    }

    // 强制下线确认框要在停用之前量：停用会结束该用户的全部会话
    await page.goto(`/sessions/${id}`);
    await page.getByRole("button", { name: "强制下线" }).click();
    const terminate = page.getByRole("alertdialog", { name: "强制下线该会话？" });
    await expect(terminate).toBeVisible();
    await settle(terminate);
    await expectTouchTargets(page, `[${width}] 强制下线确认框`, '[role="alertdialog"]');
    await terminate.getByRole("button", { name: "取消", exact: true }).click();
    await expect(terminate).toBeHidden();

    await page.goto(`/users/${id}`);
    await page.getByRole("button", { name: "停用用户" }).click();
    const disable = page.getByRole("alertdialog");
    await expect(disable).toBeVisible();
    await settle(disable);
    await expectTouchTargets(page, `[${width}] 停用确认框`, '[role="alertdialog"]');
    await disable.getByRole("button", { name: "停用", exact: true }).click();
    await expect(disable).toBeHidden();

    await page.getByRole("button", { name: "启用用户" }).click();
    await expect(disable).toBeVisible();
    await settle(disable);
    await expectTouchTargets(page, `[${width}] 启用确认框`, '[role="alertdialog"]');
    await disable.getByRole("button", { name: "取消", exact: true }).click();
  });
});

test.describe("破坏性按钮的间距", () => {
  test("停用用户、强制下线及其确认键：与相邻可交互元素的间距 ≥ 8px", async ({ page, browser, baseURL }) => {
    const width = widthOf(page);
    await loginAdmin(page);
    const subject = namesFor(page, "gp");
    const id = await createUserViaUi(page, subject);
    await contexts.signIn(browser, baseURL, page, subject.accountName);

    await page.goto(`/users/${id}`);
    const disableTrigger = page.getByRole("button", { name: "停用用户" });
    expect(await minGapToNeighbours(page, disableTrigger), `[${width}] 「停用用户」与相邻元素的间距`).toBeGreaterThanOrEqual(8);
    await disableTrigger.click();
    const disableDialog = page.getByRole("alertdialog");
    await expect(disableDialog).toBeVisible();
    await settle(disableDialog);
    const disableConfirm = disableDialog.getByRole("button", { name: "停用", exact: true });
    expect(await minGapToNeighbours(page, disableConfirm), `[${width}] 停用确认键与「取消」的间距`).toBeGreaterThanOrEqual(8);
    await disableDialog.getByRole("button", { name: "取消", exact: true }).click();
    await expect(disableDialog).toBeHidden();

    await page.goto(`/sessions/${id}`);
    const terminate = page.getByRole("button", { name: "强制下线" });
    expect(await minGapToNeighbours(page, terminate), `[${width}] 「强制下线」与相邻元素的间距`).toBeGreaterThanOrEqual(8);
    await terminate.click();
    const terminateDialog = page.getByRole("alertdialog");
    await expect(terminateDialog).toBeVisible();
    await settle(terminateDialog);
    const terminateConfirm = terminateDialog.getByRole("button", { name: "强制下线", exact: true });
    expect(await minGapToNeighbours(page, terminateConfirm), `[${width}] 强制下线确认键与「取消」的间距`).toBeGreaterThanOrEqual(8);
  });
});

/** 很长的名字：64 个字符的用户名（一整串不可断的宽字符 / 64 个汉字）与 32 个字符的账号名。 */
function longNames(page: Page, extra = "") {
  const tag = `${runId}${widthOf(page)}${extra}`;
  return {
    /** 不可断：运行后缀 + 宽字符 W，凑满 64 / 32 个字符。 */
    wide: {
      userName: `R${tag}`.padEnd(64, "W"),
      accountName: `e2e.${tag}`.padEnd(32, "W"),
    },
    /** 64 个汉字（可在任意字之间断行）；账号名同样 32 个字符。 */
    cjk: {
      userName: `响应式${tag}`.padEnd(64, "长名称测试用户"),
      accountName: `e2e.c${tag}`.padEnd(32, "Q"),
    },
  };
}

test.describe("很长的名字不撑破版面", () => {
  test("64 字符用户名与 32 字符账号名：列表、详情、会话、确认框、顶栏用户菜单都不撑破版面", async ({ page, browser, baseURL }) => {
    test.setTimeout(240_000);
    const width = widthOf(page);
    await loginAdmin(page);
    const { wide, cjk } = longNames(page);
    expect(wide.userName).toHaveLength(64);
    expect(wide.accountName).toHaveLength(32);
    expect([...cjk.userName]).toHaveLength(64);
    expect(cjk.accountName).toHaveLength(32);
    const wideId = await createUserViaUi(page, wide);
    const cjkId = await createUserViaUi(page, cjk);
    // 两位长名字用户各登录一次：一个会话（在线列表、会话卡片、顶栏里的长名字）
    const wideSession = await contexts.signIn(browser, baseURL, page, wide.accountName);
    await contexts.signIn(browser, baseURL, page, cjk.accountName);

    // 创建成功后的详情（含「用户已创建」提示）
    await page.goto(`/users/${wideId}?created=1`);
    await expect(page.getByRole("heading", { level: 1 })).toContainText(wide.userName.slice(0, 20));
    await expectLayoutOk(page, `[${width}] 长名字详情（不可断）`);
    await page.goto(`/users/${cjkId}`);
    await expect(page.getByRole("heading", { level: 1 })).toContainText("响应式");
    await expectLayoutOk(page, `[${width}] 长名字详情（汉字）`);

    // 用户列表：按运行后缀搜出两位
    await page.goto(`/users?q=${encodeURIComponent(`${runId}${width}`)}`);
    await expect(page.getByRole("link", { name: wide.userName }).locator("visible=true")).toBeVisible();
    await expectLayoutOk(page, `[${width}] 长名字用户列表`);

    // 面包屑、停用确认框（标题里有长用户名）
    await page.goto(`/users/${wideId}`);
    await page.getByRole("button", { name: "停用用户" }).click();
    const disableDialog = page.getByRole("alertdialog");
    await expect(disableDialog).toBeVisible();
    await expectDialogReachable(page, disableDialog, `[${width}] 长名字停用确认框`);
    await expectLayoutOk(page, `[${width}] 长名字停用确认框`);
    await disableDialog.getByRole("button", { name: "取消", exact: true }).click();
    await expect(disableDialog).toBeHidden();

    // 在线用户列表、会话详情（会话卡片里 32 字符账号名；标题里 64 字符用户名）、强制下线确认框（描述里有长账号名）
    await page.goto("/sessions");
    await expect(online(page, wide.userName.slice(0, 12)).first()).toBeVisible();
    await expectLayoutOk(page, `[${width}] 长名字在线用户列表`);
    await page.goto(`/sessions/${wideId}`);
    await expect(page.getByRole("list", { name: "会话列表" }).getByRole("listitem")).toHaveCount(1);
    await expectLayoutOk(page, `[${width}] 长名字会话卡片`);
    await page.getByRole("button", { name: "强制下线" }).click();
    const terminateDialog = page.getByRole("alertdialog", { name: "强制下线该会话？" });
    await expect(terminateDialog).toBeVisible();
    await expectDialogReachable(page, terminateDialog, `[${width}] 长名字强制下线确认框`);
    await expectLayoutOk(page, `[${width}] 长名字强制下线确认框`);
    await terminateDialog.getByRole("button", { name: "取消", exact: true }).click();
    await page.goto(`/sessions/${cjkId}`);
    await expectLayoutOk(page, `[${width}] 长名字会话卡片（汉字）`);

    // 长名字用户自己登录后的外壳：顶栏用户名截断、菜单里换行、修改密码页里的账号名
    await expectLayoutOk(wideSession, `[${width}] 长名字用户登录后的列表`);
    await openUserMenu(wideSession, wide.userName);
    await expectLayoutOk(wideSession, `[${width}] 长名字用户的用户菜单`);
    await wideSession.keyboard.press("Escape");
    await wideSession.goto("/account/password");
    await expect(wideSession.getByRole("main")).toContainText(wide.accountName);
    await expectLayoutOk(wideSession, `[${width}] 长名字用户的修改密码页`);
  });

  test("新建表单里键入超长值（用户名、账号名）：输入框不撑破版面", async ({ page }) => {
    const width = widthOf(page);
    await loginAdmin(page);
    await page.goto("/users/new");
    await page.getByLabel("用户名", { exact: true }).fill("W".repeat(64));
    await page.getByLabel("账号名", { exact: true }).fill("W".repeat(32));
    await page.getByLabel("初始密码", { exact: true }).fill(`${PASSWORD}${"x".repeat(60)}`);
    await expectLayoutOk(page, `[${width}] 新建表单里的超长值`);
  });
});

test.describe("矮视口（横屏手机，高 320）", () => {
  test("确认框落在视口内、可滚动，取消与确认仍可点；长用户名也一样", async ({ page, browser, baseURL }) => {
    test.setTimeout(180_000);
    const width = widthOf(page);
    await loginAdmin(page);
    const { wide } = longNames(page, "s"); // 与上一个用例的长名字用户区分开（账号名不能重复）
    const id = await createUserViaUi(page, wide);
    await contexts.signIn(browser, baseURL, page, wide.accountName);

    await page.setViewportSize({ width, height: 320 });
    await page.goto(`/users/${id}`);
    await page.getByRole("button", { name: "停用用户" }).click();
    const disableDialog = page.getByRole("alertdialog");
    await expect(disableDialog).toBeVisible();
    await expectDialogReachable(page, disableDialog, `[${width}×320] 停用确认框`);
    await disableDialog.getByRole("button", { name: "停用", exact: true }).scrollIntoViewIfNeeded();
    await disableDialog.getByRole("button", { name: "取消", exact: true }).click();
    await expect(disableDialog).toBeHidden();

    await page.goto(`/sessions/${id}`);
    await page.getByRole("button", { name: "强制下线" }).click();
    const terminateDialog = page.getByRole("alertdialog", { name: "强制下线该会话？" });
    await expect(terminateDialog).toBeVisible();
    await expectDialogReachable(page, terminateDialog, `[${width}×320] 强制下线确认框`);
    // 确认键也要能点到（不真的确认，只检查可点：Playwright 的 click 会检查「在视口内、未被遮挡」；trial 不触发点击）
    await terminateDialog.getByRole("button", { name: "强制下线", exact: true }).click({ trial: true });
    await terminateDialog.getByRole("button", { name: "取消", exact: true }).click();
    await expect(terminateDialog).toBeHidden();
  });
});
