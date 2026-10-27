import { type Page } from "@playwright/test";
import { expect, login, test } from "./support/fixtures";
import { psql } from "./support/stack";
import {
  Contexts,
  createUserViaUi,
  expectNoSeriousViolations,
  focusInside,
  hasVisibleFocusIndicator,
  isPhone,
  mainNav,
  namesFor,
  openNav,
  openUserMenu,
  PASSWORD,
  runId,
  settle,
  tabTo,
  widthOf,
} from "./support/responsive";

/**
 * US9：无障碍（quickstart S12；FR-036）。
 * 前提：栈已按 devops/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）。在六个视口 project 上各跑一遍。
 *
 * - axe（`@axe-core/playwright`）：serious / critical 必须为 0，覆盖每个页面与状态：登录（含错误、会话结束提示）、用户列表（含无结果）、
 *   用户详情（正常 / 已停用 / 无账号 / 不存在）、新建表单（含错误与账号名冲突）、会话管理列表、会话卡片、没有会话的用户、
 *   修改密码（含各类错误与成功）、404、用户菜单、手机的导航抽屉、停用 / 启用 / 强制下线确认框。
 *   「列表为空」（还没有用户 / 当前没有在线用户）无法在共享栈上出现（ADMIN 自己就是用户与在线用户），其标记与「无搜索结果」共用同一个
 *   StateCard 组件，由无结果状态与「没有会话的用户」的 axe 覆盖。
 * - 仅键盘：登录、搜索、打开用户、打开确认框、取消、确认；新建用户；经导航与用户菜单；对话框焦点限制、Esc、焦点回到触发元素；
 *   手机抽屉焦点限制、Esc 关闭并把焦点还给菜单按钮；每个 Tab 停靠点都有清晰可见的焦点指示。
 * 名字带本次运行与视口的后缀，刻意不含其它规格造数用户的子串（见 support/responsive.ts）。
 */

const contexts = new Contexts();
test.afterEach(() => contexts.closeAll());

const MISSING_ID = "e2e00009-0000-4000-8000-0000000000ff";

/** 以 ADMIN 登录（`login()` 不等待，必须等跳转完成）。 */
async function loginAdmin(page: Page) {
  await login(page);
  await expect(page).toHaveURL(/\/users$/);
}

test.describe("axe：登录页", () => {
  test("登录页：正常、必填错误、账号名或密码错误、会话结束提示", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("button", { name: "登录" })).toBeVisible();
    await expectNoSeriousViolations(page, "登录页");

    await page.getByRole("button", { name: "登录" }).click();
    await expect(page.getByText("请填写账号名")).toBeVisible();
    await expectNoSeriousViolations(page, "登录页（必填错误）");

    // 不存在的账号名 + 错密码：只计一次失败（按账号名与来源地址计数），远低于限流阈值
    await page.getByLabel("账号名").fill(`e2e.nobody.${runId}${widthOf(page)}`);
    await page.getByLabel("密码").fill("Wrong-Pass-123");
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page.getByRole("main").getByRole("alert").filter({ hasText: "账号名或密码错误" })).toBeVisible();
    await expectNoSeriousViolations(page, "登录页（账号名或密码错误）");

    await page.goto("/login?reason=session_ended");
    await expect(page.getByText("会话已结束，请重新登录。")).toBeVisible();
    await expectNoSeriousViolations(page, "登录页（会话结束提示）");
  });

  test("404 页", async ({ page }) => {
    await page.goto("/no-such-page");
    await expect(page.getByRole("heading", { level: 1, name: "页面不存在" })).toBeVisible();
    await expectNoSeriousViolations(page, "404 页");
  });
});

test.describe("axe：用户管理", () => {
  test("用户列表、无结果、外壳菜单（用户菜单 / 手机抽屉）、用户详情各状态、不存在的用户", async ({ page }) => {
    await loginAdmin(page);
    const admin = "系统管理员";

    // 列表
    await expect(page.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();
    await expectNoSeriousViolations(page, "用户列表");

    // 无搜索结果
    await page.goto(`/users?q=${encodeURIComponent(`没有这个人${runId}`)}`);
    await expect(page.getByText(/没有找到匹配/)).toBeVisible();
    await expectNoSeriousViolations(page, "用户列表（无结果）");

    // 用户菜单打开
    await page.goto("/users");
    await openUserMenu(page, admin);
    await expectNoSeriousViolations(page, "用户菜单（打开）");
    await page.keyboard.press("Escape");

    // 手机的导航抽屉打开
    if (isPhone(page)) {
      await openNav(page);
      await expectNoSeriousViolations(page, "导航抽屉（打开）");
      await page.keyboard.press("Escape");
    }

    // 详情：新建一个用户（有账号），再造一个没有账号的用户
    const subject = namesFor(page, "ax");
    const id = await createUserViaUi(page, subject);
    await expect(page.getByRole("status").filter({ hasText: "用户已创建" })).toBeVisible();
    await expectNoSeriousViolations(page, "用户详情（刚创建）");

    const noAccount = `响应式无号${runId}${widthOf(page)}`;
    const noAccountId = crypto.randomUUID();
    psql(`INSERT INTO app_user (id, name, status, updated_at) VALUES ('${noAccountId}', '${noAccount}', 'ACTIVE', now());`);
    await page.goto(`/users/${noAccountId}`);
    await expect(page.getByText("该用户暂无关联账号")).toBeVisible();
    await expectNoSeriousViolations(page, "用户详情（无账号）");

    // 停用确认框、停用后的详情、启用确认框
    await page.goto(`/users/${id}`);
    await page.getByRole("button", { name: "停用用户" }).click();
    const disableDialog = page.getByRole("alertdialog", { name: `停用用户「${subject.userName}」？` });
    await expect(disableDialog).toBeVisible();
    await settle(disableDialog);
    await expectNoSeriousViolations(page, "停用确认框");
    await disableDialog.getByRole("button", { name: "停用", exact: true }).click();
    await expect(disableDialog).toBeHidden();
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("已停用");
    await expectNoSeriousViolations(page, "用户详情（已停用）");

    await page.getByRole("button", { name: "启用用户" }).click();
    const enableDialog = page.getByRole("alertdialog", { name: `启用用户「${subject.userName}」？` });
    await expect(enableDialog).toBeVisible();
    await settle(enableDialog);
    await expectNoSeriousViolations(page, "启用确认框");
    await enableDialog.getByRole("button", { name: "取消", exact: true }).click();
    await expect(enableDialog).toBeHidden();

    // 不存在的用户
    await page.goto(`/users/${MISSING_ID}`);
    await expect(page.getByText("用户不存在", { exact: true }).first()).toBeVisible();
    await expectNoSeriousViolations(page, "用户详情（不存在）");
  });

  test("新建用户表单：正常、必填错误、账号名冲突", async ({ page }) => {
    await loginAdmin(page);
    const subject = namesFor(page, "nf");
    await page.goto("/users/new");
    await expect(page.getByRole("heading", { level: 1, name: "新建用户" })).toBeVisible();
    await expectNoSeriousViolations(page, "新建用户表单");

    await page.getByRole("button", { name: /^创建/ }).click();
    await expect(page.getByText("请填写用户名")).toBeVisible();
    await expectNoSeriousViolations(page, "新建用户表单（必填错误）");

    await createUserViaUi(page, subject);
    // 同一账号名再建一次：冲突提示
    await page.goto("/users/new");
    await page.getByLabel("用户名", { exact: true }).fill(`${subject.userName}重复`);
    await page.getByLabel("账号名", { exact: true }).fill(subject.accountName);
    await page.getByLabel("初始密码", { exact: true }).fill(PASSWORD);
    await page.getByRole("button", { name: /^创建/ }).click();
    await expect(page.getByLabel("账号名", { exact: true })).toHaveAttribute("aria-invalid", "true");
    await expectNoSeriousViolations(page, "新建用户表单（账号名冲突）");
  });
});

test.describe("axe：会话管理", () => {
  test("在线用户列表、会话卡片、强制下线确认框、自己的会话、没有会话的用户", async ({ page, browser, baseURL }) => {
    await loginAdmin(page);
    const target = namesFor(page, "ss");
    const idle = namesFor(page, "id");
    const targetId = await createUserViaUi(page, target);
    const idleId = await createUserViaUi(page, idle);
    await contexts.signIn(browser, baseURL, page, target.accountName);

    await page.goto("/sessions");
    await expect(page.getByRole("heading", { level: 1, name: "会话管理" })).toBeVisible();
    await expectNoSeriousViolations(page, "在线用户列表");

    await page.goto(`/sessions/${targetId}`);
    await expect(page.getByRole("list", { name: "会话列表" }).getByRole("listitem")).toHaveCount(1);
    await expectNoSeriousViolations(page, "会话卡片");

    await page.getByRole("button", { name: "强制下线" }).click();
    const dialog = page.getByRole("alertdialog", { name: "强制下线该会话？" });
    await expect(dialog).toBeVisible();
    await settle(dialog);
    await expectNoSeriousViolations(page, "强制下线确认框");
    await dialog.getByRole("button", { name: "取消", exact: true }).click();
    await expect(dialog).toBeHidden();

    // 自己的当前会话：强制下线入口禁用并说明原因
    await page.goto("/sessions");
    const adminLink = page
      .getByRole("list", { name: "在线用户卡片" })
      .or(page.getByRole("table", { name: "在线用户列表" }))
      .getByRole("link", { name: "系统管理员" })
      .first();
    await adminLink.click();
    await expect(page.getByText("当前会话", { exact: true })).toBeVisible();
    await expectNoSeriousViolations(page, "会话卡片（自己的当前会话，入口禁用）");

    // 没有会话的用户
    await page.goto(`/sessions/${idleId}`);
    await expect(page.getByText("该用户当前没有在线会话")).toBeVisible();
    await expectNoSeriousViolations(page, "会话详情（没有会话）");
  });
});

test.describe("axe：修改密码", () => {
  test("表单：正常、必填错误、两次不一致、当前密码错误、成功", async ({ page, browser, baseURL }) => {
    await loginAdmin(page);
    const subject = namesFor(page, "pw");
    await createUserViaUi(page, subject);
    const user = await contexts.signIn(browser, baseURL, page, subject.accountName);

    await user.goto("/account/password");
    await expect(user.getByRole("heading", { level: 1, name: "修改密码" })).toBeVisible();
    await expectNoSeriousViolations(user, "修改密码表单");

    const submit = user.getByRole("button", { name: /^修改/ });
    await submit.click();
    await expect(user.getByText("请填写当前密码")).toBeVisible();
    await expectNoSeriousViolations(user, "修改密码表单（必填错误）");

    const fill = async (current: string, next: string, confirm: string) => {
      await user.getByLabel("当前密码", { exact: true }).fill(current);
      await user.getByLabel("新密码", { exact: true }).fill(next);
      await user.getByLabel("确认新密码", { exact: true }).fill(confirm);
      await submit.click();
      await expect(submit).not.toHaveText(/修改中/);
    };
    await fill(PASSWORD, "E2e-Resp-Pass-3", "E2e-Resp-Pass-3x");
    await expect(user.getByText("两次输入的新密码不一致")).toBeVisible();
    await expectNoSeriousViolations(user, "修改密码表单（两次不一致）");

    await fill("Wrong-Current-9", "E2e-Resp-Pass-3", "E2e-Resp-Pass-3");
    await expect(user.getByLabel("当前密码", { exact: true })).toHaveAttribute("aria-invalid", "true");
    await expectNoSeriousViolations(user, "修改密码表单（当前密码错误）");

    await fill(PASSWORD, "E2e-Resp-Pass-3", "E2e-Resp-Pass-3");
    await expect(user.getByRole("main").getByRole("status").filter({ hasText: "密码已修改" })).toBeVisible();
    await expectNoSeriousViolations(user, "修改密码（成功）");
  });
});

// ---------------------------------------------------------------------------------------------------------------------
// 仅键盘
// ---------------------------------------------------------------------------------------------------------------------

test.describe("仅键盘", () => {
  test("登录：Tab 到账号名与密码，Enter 提交，进入用户列表", async ({ page, browser, baseURL }) => {
    await loginAdmin(page);
    const subject = namesFor(page, "kl");
    await createUserViaUi(page, subject);
    const fresh = await contexts.open(browser, baseURL, page);

    await fresh.goto("/login");
    await tabTo(fresh, fresh.getByLabel("账号名"), { max: 3 });
    await fresh.keyboard.type(subject.accountName);
    await fresh.keyboard.press("Tab");
    await expect(fresh.getByLabel("密码")).toBeFocused();
    await fresh.keyboard.type(PASSWORD);
    await fresh.keyboard.press("Enter");
    await expect(fresh).toHaveURL(/\/users$/);
  });

  test("搜索 → 打开用户 → 打开确认框（Space）→ 焦点限制 → Esc 关闭并回到触发元素 → 再开、Tab 到取消、Enter 取消 → 再开、确认", async ({ page }) => {
    await loginAdmin(page);
    const subject = namesFor(page, "ks");
    const id = await createUserViaUi(page, subject);

    // 搜索：键盘聚焦搜索框、输入、Enter
    await page.goto("/users");
    const search = page.getByRole("searchbox", { name: "搜索用户名或账号名" });
    await tabTo(page, search);
    await page.keyboard.type(subject.userName);
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(new RegExp(`/users\\?q=`));

    // 打开用户：Tab 到结果链接，Enter
    const link = page.getByRole("link", { name: subject.userName }).locator("visible=true");
    await tabTo(page, link);
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(new RegExp(`/users/${id}$`));
    await expect(page.getByRole("heading", { level: 1, name: subject.userName })).toBeVisible();

    // 打开确认框：Tab 到「停用用户」，Space
    const trigger = page.getByRole("button", { name: "停用用户" });
    await tabTo(page, trigger);
    await page.keyboard.press("Space");
    const dialog = page.getByRole("alertdialog", { name: `停用用户「${subject.userName}」？` });
    await expect(dialog).toBeVisible();
    await settle(dialog);
    expect(await focusInside(page, '[role="alertdialog"]'), "打开后焦点应在对话框内").toBe(true);

    // 焦点限制：向前、向后各按若干次（多于可聚焦元素个数）都不离开对话框
    for (const key of ["Tab", "Tab", "Tab", "Tab", "Shift+Tab", "Shift+Tab", "Shift+Tab", "Shift+Tab", "Shift+Tab"]) {
      await page.keyboard.press(key);
      expect(await focusInside(page, '[role="alertdialog"]'), `按 ${key} 后焦点离开了对话框`).toBe(true);
    }

    // Esc：关闭，焦点回到触发元素，用户没被停用
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
    await expect(trigger).toBeFocused();
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("正常");

    // 再开（Enter），Tab 到「取消」，Enter：关闭，焦点回到触发元素
    await page.keyboard.press("Enter");
    await expect(dialog).toBeVisible();
    await settle(dialog);
    await tabTo(page, dialog.getByRole("button", { name: "取消", exact: true }), { max: 4 });
    await page.keyboard.press("Enter");
    await expect(dialog).toBeHidden();
    await expect(trigger).toBeFocused();
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("正常");

    // 再开，Tab 到「停用」，Enter：确认生效；对话框关闭后焦点落在仍存在的按钮上（不丢给 body）
    await page.keyboard.press("Space");
    await expect(dialog).toBeVisible();
    await settle(dialog);
    await tabTo(page, dialog.getByRole("button", { name: "停用", exact: true }), { max: 4 });
    await page.keyboard.press("Enter");
    await expect(dialog).toBeHidden();
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("已停用");
    await expect(page.getByRole("button", { name: "启用用户" })).toBeFocused();
  });

  test("新建用户：Tab 逐个填写，Enter 提交", async ({ page }) => {
    await loginAdmin(page);
    const subject = namesFor(page, "kn");
    await page.goto("/users/new");
    await tabTo(page, page.getByLabel("用户名", { exact: true }));
    await page.keyboard.type(subject.userName);
    await page.keyboard.press("Tab");
    await expect(page.getByLabel("账号名", { exact: true })).toBeFocused();
    await page.keyboard.type(subject.accountName);
    await page.keyboard.press("Tab");
    await expect(page.getByLabel("初始密码", { exact: true })).toBeFocused();
    await page.keyboard.type(PASSWORD);
    await page.keyboard.press("Tab");
    await expect(page.getByRole("button", { name: /^创建/ })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}\?created=1$/);
  });

  test("强制下线：Tab 到按钮、Space 打开、Esc 关闭回到按钮；再开并用键盘确认", async ({ page, browser, baseURL }) => {
    await loginAdmin(page);
    const target = namesFor(page, "kt");
    const id = await createUserViaUi(page, target);
    const victim = await contexts.signIn(browser, baseURL, page, target.accountName);

    await page.goto(`/sessions/${id}`);
    const trigger = page.getByRole("button", { name: "强制下线" });
    await tabTo(page, trigger);
    await page.keyboard.press("Space");
    const dialog = page.getByRole("alertdialog", { name: "强制下线该会话？" });
    await expect(dialog).toBeVisible();
    await settle(dialog);
    for (const key of ["Tab", "Tab", "Tab", "Shift+Tab", "Shift+Tab", "Shift+Tab"]) {
      await page.keyboard.press(key);
      expect(await focusInside(page, '[role="alertdialog"]'), `按 ${key} 后焦点离开了对话框`).toBe(true);
    }
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
    await expect(trigger).toBeFocused();

    await page.keyboard.press("Enter");
    await expect(dialog).toBeVisible();
    await settle(dialog);
    await tabTo(page, dialog.getByRole("button", { name: "强制下线", exact: true }), { max: 4 });
    await page.keyboard.press("Enter");
    await expect(page.getByRole("status").filter({ hasText: "会话已强制下线。" })).toBeVisible();
    // 对方的下一次请求回登录页
    await victim.goto("/users");
    await expect(victim).toHaveURL(/\/login/);
  });

  test("经导航与用户菜单：Tab / Enter 进入会话管理；Enter 打开用户菜单、方向键选择、Esc 关闭并回到触发元素", async ({ page }) => {
    await loginAdmin(page);

    // 「跳到主要内容」是第一个 Tab 停靠点（只在获得焦点时可见）
    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "跳到主要内容" })).toBeFocused();

    // 到「会话管理」：手机先聚焦菜单按钮、Enter 打开抽屉
    if (isPhone(page)) {
      const menuButton = page.getByRole("button", { name: "打开导航菜单" });
      await tabTo(page, menuButton);
      await page.keyboard.press("Enter");
      await expect(page.getByRole("dialog")).toBeVisible();
      await settle(page.getByRole("dialog"));
    }
    await tabTo(page, mainNav(page).getByRole("link", { name: "会话管理" }));
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/sessions$/);

    // 用户菜单
    const trigger = page.getByRole("button", { name: /系统管理员/ });
    await tabTo(page, trigger);
    await page.keyboard.press("Enter");
    const menu = page.getByRole("menu");
    await expect(menu).toBeVisible();
    await settle(menu);
    expect(await focusInside(page, '[role="menu"]'), "菜单打开后焦点应在菜单内").toBe(true);
    await page.keyboard.press("ArrowDown");
    expect(await focusInside(page, '[role="menu"]')).toBe(true);
    await page.keyboard.press("Escape");
    await expect(menu).toBeHidden();
    await expect(trigger).toBeFocused();
  });

  test("手机的导航抽屉：Enter 打开、焦点限制在抽屉内、Esc 关闭并回到菜单按钮", async ({ page }) => {
    test.skip(!isPhone(page), "抽屉只在手机（< 768）上存在；桌面 / 平板的导航是常驻的");
    await loginAdmin(page);
    const menuButton = page.getByRole("button", { name: "打开导航菜单" });
    await tabTo(page, menuButton);
    await page.keyboard.press("Enter");
    const drawer = page.getByRole("dialog");
    await expect(drawer).toBeVisible();
    await settle(drawer);
    expect(await focusInside(page, '[role="dialog"]'), "抽屉打开后焦点应在抽屉内").toBe(true);

    for (const key of ["Tab", "Tab", "Tab", "Tab", "Shift+Tab", "Shift+Tab", "Shift+Tab", "Shift+Tab"]) {
      await page.keyboard.press(key);
      expect(await focusInside(page, '[role="dialog"]'), `按 ${key} 后焦点离开了抽屉`).toBe(true);
    }

    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(menuButton).toBeFocused();

    // 再开、点导航项进入页面：抽屉关闭
    await page.keyboard.press("Enter");
    await expect(drawer).toBeVisible();
    await settle(drawer);
    await tabTo(page, mainNav(page).getByRole("link", { name: "会话管理" }), { max: 6 });
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/sessions$/);
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });

  test("焦点清晰可见：用户列表、新建表单、会话卡片与确认框里的每个 Tab 停靠点都有焦点指示", async ({ page, browser, baseURL }) => {
    await loginAdmin(page);
    const target = namesFor(page, "kf");
    const id = await createUserViaUi(page, target);
    await contexts.signIn(browser, baseURL, page, target.accountName);

    const sweep = async (where: string, stops: number) => {
      const seen: string[] = [];
      for (let i = 0; i < stops; i++) {
        await page.keyboard.press("Tab");
        const info = await page.evaluate(() => {
          const el = document.activeElement;
          return el && el !== document.body ? `${el.tagName.toLowerCase()}「${(el.getAttribute("aria-label") ?? el.textContent ?? "").trim().slice(0, 20)}」` : "";
        });
        if (!info) break; // 焦点出了页面（回到浏览器界面），一圈走完
        expect(await hasVisibleFocusIndicator(page), `${where}：${info} 获得焦点但没有可见的焦点指示`).toBe(true);
        seen.push(info);
      }
      expect(seen.length, `${where}：至少应有几个 Tab 停靠点`).toBeGreaterThan(3);
    };

    await page.goto("/users");
    await sweep("用户列表", 14);
    await page.goto("/users/new");
    await sweep("新建用户表单", 12);
    await page.goto(`/sessions/${id}`);
    await sweep("会话卡片", 10);

    await page.getByRole("button", { name: "强制下线" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await settle(dialog);
    await sweep("强制下线确认框", 4);
  });
});
