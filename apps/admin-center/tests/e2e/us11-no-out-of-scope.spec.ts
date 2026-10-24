import { type Page } from "@playwright/test";
import { ADMIN, expect, login, seedUsers, test, userIdOfAccount, USERS_SEED } from "./support/fixtures";
import { Contexts, createUserViaUi, isDesktop, isPhone, openNav, openUserMenu, runId, settle, widthOf } from "./support/responsive";

/**
 * US11：没有范围外的入口（FR-041、SC-015；quickstart S18）。
 * 遍历**所有可达页面**（登录、用户列表、用户详情（含已停用 / 无账号 / 自己）、新建、在线用户、用户会话详情、修改密码、404）与
 * 三类浮层（顶栏用户菜单、手机导航抽屉、停用 / 启用 / 强制下线的确认框），在当前视口（桌面 1440 与手机 375 是必测的）上：
 *
 * 1. 取每个页面「可交互元素」的快照——浏览器算出的无障碍树里所有链接、按钮、菜单项、输入框（角色 + 无障碍名称 + 链接地址），
 *    加上表单的提交方式与地址、隐藏字段名——与**显式的允许清单**逐页比对：多出任何一个控件（哪怕不是被禁止的那几类），或少了一个，都失败。
 *    所以日后新增的未经评审的入口会被这里抓到，而不只是抓已知的几种。
 * 2. 在全部快照上再做一遍禁止项检查：编辑用户名、删除用户、重置他人密码、找回密码、绑定 / 解绑账号、批量或一键下线，
 *    按名称、地址、整页可见文本三路检查（即使将来有人改了允许清单，也不会让这些入口混进去）。
 *
 * 用户名 / 用户标识 / 分页链接是动态的，快照里统一写成「‹用户名›」「:id」，并单独断言分页链接的形状（有多少页取决于栈里累积的数据）。
 *
 * 造数：用 US2 的固定造数（无账号 / 已停用 / 多账号用户，只读）和经界面建的「原型核对」用户（名字与其它规格的造数子串不重叠）。
 */

const ctxs = new Contexts();

test.beforeAll(() => {
  seedUsers();
});

test.afterEach(async () => {
  await ctxs.closeAll();
});

// ---------------------------------------------------------------------------------------------------------------------
// 快照
// ---------------------------------------------------------------------------------------------------------------------

const CONTROL_ROLES = new Set([
  "link",
  "button",
  "textbox",
  "searchbox",
  "combobox",
  "listbox",
  "option",
  "checkbox",
  "radio",
  "switch",
  "slider",
  "spinbutton",
  "menuitem",
  "menuitemcheckbox",
  "menuitemradio",
  "tab",
  "treeitem",
]);

const UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
/** 分页链接：第 1 页的地址不带 `page` 参数。 */
const PAGER = /^link "(上一页|下一页)" -> \/(users|sessions)(\?\S*)?$/;

/** 把 Playwright 的 ariaSnapshot（YAML）里的控件取出来：`角色 "名称"[ [disabled]][ -> 地址]`；用户名与标识归一化。 */
function controlsFromAria(yaml: string): string[] {
  const lines = yaml.split("\n");
  const out: string[] = [];
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^(\s*)- ([a-z]+)(?: ("(?:[^"\\]|\\.)*"))?((?: \[[^\]]+\])*)/);
    if (!m || !CONTROL_ROLES.has(m[2])) continue;
    const [, indent, role, quoted, attrs] = m;
    let name = quoted ? (JSON.parse(quoted) as string) : "‹无名称›";
    let url = "";
    if (role === "link") {
      for (let j = i + 1; j < lines.length && lines[j].startsWith(`${indent}  `); j++) {
        const u = lines[j].match(/^\s*- \/url: (.*)$/);
        if (u) {
          url = u[1].replace(/^"|"$/g, "");
          break;
        }
      }
      // 列表里指向某个用户的链接：名称是用户名（动态）
      const target = url.match(new RegExp(`^/(users|sessions)/${UUID}$`));
      if (target) {
        name = "‹用户名›";
        url = `/${target[1]}/:id`;
      }
    }
    const disabled = /\[disabled\]/.test(attrs) ? " [disabled]" : "";
    // 面包屑里的当前页是 `role="link" aria-disabled="true" aria-current="page"` 的文字（shadcn 的 BreadcrumbPage），没有地址，不是入口
    if (role === "link" && !url && disabled) continue;
    out.push(`${role} "${name}"${disabled}${url ? ` -> ${url}` : ""}`);
  }
  return out;
}

/** 页面里的表单（提交方式 + 地址；React 的 `javascript:` 占位地址表示由 Server Action 处理）与隐藏字段名。 */
async function formsOf(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const out: string[] = [];
    for (const form of document.querySelectorAll("form")) {
      const action = form.getAttribute("action") ?? "";
      if (action && !action.startsWith("javascript:")) out.push(`form ${(form.getAttribute("method") ?? "get").toUpperCase()} ${action}`);
      else out.push("form server-action");
    }
    for (const input of document.querySelectorAll<HTMLInputElement>('input[type="hidden"]')) {
      if (!input.name.startsWith("$ACTION")) out.push(`hidden ${input.name}`);
    }
    return out;
  });
}

interface Snapshot {
  controls: string[];
  pager: string[];
  text: string;
}

async function snapshot(page: Page): Promise<Snapshot> {
  const all = [...controlsFromAria(await page.locator("body").ariaSnapshot()), ...(await formsOf(page))];
  const text = await page.evaluate(() => document.body.innerText);
  return { controls: [...new Set(all.filter((c) => !PAGER.test(c)))].sort(), pager: all.filter((c) => PAGER.test(c)), text };
}

// ---------------------------------------------------------------------------------------------------------------------
// 禁止项（范围外）
// ---------------------------------------------------------------------------------------------------------------------

/** 名称：编辑用户名、删除用户、重置他人密码、找回密码、绑定账号、批量 / 一键下线。「修改密码」（自己的）、「强制下线」（单个会话）是范围内的。 */
const FORBIDDEN_NAME =
  /编辑|重命名|改名|修改(用户|名称|姓名)|删除|移除|注销|重置|找回|忘记|恢复密码|绑定|解绑|添加账号|新增账号|增加账号|批量|全部下线|全部退出|退出全部|下线全部|一键|强制下线全部|edit|delete|remove|reset|forgot|recover|bind|rename|batch|logout.?all|sign.?out.?all/i;
/** 地址：同类的路径段或参数。 */
const FORBIDDEN_URL = /(^|[/?&=_-])(edit|delete|remove|reset|forgot|recover|bind|unbind|rename|batch|logout-?all|terminate-?all|revoke-?all|sessions\/all)($|[/?&=_-])/i;
/** 整页可见文本里不该出现的短语（说明、提示也不该暗示这些功能存在）。 */
const FORBIDDEN_TEXT = /忘记密码|找回密码|重置(他人|用户)?密码|删除用户|编辑用户|修改用户名|绑定账号|解绑账号|批量下线|全部下线|一键下线|强制下线全部/;

function expectNoOutOfScope(where: string, s: Snapshot): void {
  for (const control of [...s.controls, ...s.pager]) {
    expect.soft(control, `${where}：出现范围外的入口（名称）`).not.toMatch(FORBIDDEN_NAME);
    const url = control.split(" -> ")[1] ?? "";
    expect.soft(url, `${where}：出现范围外的入口（地址）`).not.toMatch(FORBIDDEN_URL);
  }
  expect.soft(s.text, `${where}：页面文字暗示了范围外的功能`).not.toMatch(FORBIDDEN_TEXT);
}

// ---------------------------------------------------------------------------------------------------------------------
// 允许清单
// ---------------------------------------------------------------------------------------------------------------------

const SKIP_LINK = 'link "跳到主要内容" -> #main';

const NAV = ['link "用户管理" -> /users', 'link "会话管理" -> /sessions'];

/** 已登录外壳（顶栏、导航）在当前视口上的控件；手机的导航在抽屉里，抽屉关着时不在页面里。 */
function shell(page: Page, userName: string): string[] {
  const menuButton = `button "${userName}"`; // 顶栏用户菜单的触发按钮，名称是登录者的用户名
  if (isPhone(page)) return [SKIP_LINK, menuButton, 'button "打开导航菜单"'];
  return [SKIP_LINK, menuButton, ...(isDesktop(page) ? ['button "收起侧边栏"'] : []), ...NAV];
}

const CRUMB_USERS = 'link "用户管理" -> /users'; // 面包屑里的链接与侧边栏的导航项同名同址，集合里只算一个
const CRUMB_SESSIONS = 'link "会话管理" -> /sessions';

/** 用户详情页的内容区。 */
const DETAIL_ACTIVE = ['button "停用用户"'];
const DETAIL_SELF = ['button "停用用户" [disabled]'];
const DETAIL_DISABLED = ['button "启用用户"'];

const EXPECTED = {
  login: ['button "登录"', 'textbox "账号名"', 'textbox "密码"', "form server-action", "hidden next"],

  // 列表页自己是面包屑里的当前页（文字，没有链接）
  usersList: [
    'link "新建用户" -> /users/new',
    'searchbox "搜索用户名或账号名"',
    'button "搜索"',
    'link "‹用户名›" -> /users/:id',
    "form GET /users",
  ],
  usersEmpty: ['link "新建用户" -> /users/new', 'searchbox "搜索用户名或账号名"', 'button "搜索"', 'link "清除搜索" -> /users', "form GET /users"],
  userDetailActive: [CRUMB_USERS, ...DETAIL_ACTIVE],
  userDetailSelf: [CRUMB_USERS, ...DETAIL_SELF],
  userDetailDisabled: [CRUMB_USERS, ...DETAIL_DISABLED],
  userNotFound: [CRUMB_USERS, 'link "返回用户列表" -> /users'],
  newUser: [
    CRUMB_USERS,
    'textbox "用户名"',
    'textbox "账号名"',
    'textbox "初始密码"',
    'button "创建用户"',
    'link "取消" -> /users',
    "form server-action",
    "hidden idempotencyKey",
  ],

  sessionsList: ['link "‹用户名›" -> /sessions/:id'],
  sessionDetail: [CRUMB_SESSIONS, 'button "强制下线"'],
  sessionDetailOwn: [CRUMB_SESSIONS, 'button "强制下线" [disabled]'],
  sessionDetailEmpty: [CRUMB_SESSIONS, 'link "返回在线用户" -> /sessions'],
  sessionUserNotFound: [CRUMB_SESSIONS, 'link "返回用户列表" -> /users'],

  password: [
    'textbox "当前密码"',
    'textbox "新密码"',
    'textbox "确认新密码"',
    'button "修改密码"',
    'link "取消" -> /users',
    "form server-action",
  ],

  notFound: ['link "回到用户管理" -> /users'],

  // 浮层
  // 手机的导航抽屉是模态的：背景页面不在无障碍树里，只剩抽屉里的两个导航项（搜索表单是 DOM 里的，仍计入）
  drawer: [...NAV, "form GET /users"],
  userMenu: ['menuitem "修改密码"', 'menuitem "退出登录"', "form POST /logout"],
  disableDialog: ['button "取消"', 'button "停用"'],
  enableDialog: ['button "取消"', 'button "启用"'],
  terminateDialog: ['button "取消"', 'button "强制下线"'],
};

type Key = keyof typeof EXPECTED;

const sorted = (items: string[]) => [...new Set(items)].sort();

// ---------------------------------------------------------------------------------------------------------------------
// 遍历
// ---------------------------------------------------------------------------------------------------------------------

test("遍历所有页面与浮层：控件与允许清单逐页一致，且没有范围外的入口", async ({ browser, baseURL, page }) => {
  test.setTimeout(240_000);
  const width = widthOf(page);

  // 造数：一个有会话的用户、一个没有会话的用户（都经界面建，账号只在一个上下文里登录）
  await login(page);
  await expect(page).toHaveURL(/\/users$/);
  const withSession = { userName: `原型核对全站甲${runId}${width}`, accountName: `e2e.p11.os${runId}${width}a` };
  const withoutSession = { userName: `原型核对全站乙${runId}${width}`, accountName: `e2e.p11.os${runId}${width}b` };
  const withSessionId = await createUserViaUi(page, withSession);
  const withoutSessionId = await createUserViaUi(page, withoutSession);
  const ownUser = { userName: `原型核对全站丙${runId}${width}`, accountName: `e2e.p11.os${runId}${width}c` };
  const ownUserId = await createUserViaUi(page, ownUser);
  await ctxs.signIn(browser, baseURL, page, withSession.accountName);
  const adminId = userIdOfAccount(ADMIN.account);

  const visited: string[] = [];
  /** 比对一个页面或浮层：多了 / 少了 / 范围外，都带页面名报出来（软断言：一次跑完能看到所有不符的地方）。 */
  async function check(where: string, current: Page, key: Key, extra: string[] = [], opts: { withShell?: boolean; userName?: string } = {}) {
    visited.push(where);
    const snap = await snapshot(current);
    const withShell = opts.withShell ?? true;
    const expected = sorted([...(withShell ? shell(current, opts.userName ?? ADMIN.name) : []), ...EXPECTED[key], ...extra]);
    expect.soft(snap.controls, `${where}：控件应与允许清单一致`).toEqual(expected);
    expectNoOutOfScope(where, snap);
    return snap;
  }
  const open = async (path: string) => {
    await page.goto(path);
    await expect(page).toHaveURL(new RegExp(path.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "$"));
    await expect(page.getByRole("main").or(page.locator("#main")).first()).toBeVisible();
  };

  // ---- 登录页（匿名，独立上下文）----
  const anon = await ctxs.open(browser, baseURL, page);
  await anon.goto("/login");
  await expect(anon.getByRole("button", { name: "登录" })).toBeVisible();
  await check("登录页", anon, "login", [], { withShell: false });
  await anon.goto("/login?reason=session_ended");
  await expect(anon.getByText("会话已结束，请重新登录。")).toBeVisible();
  await check("登录页（会话已结束提示）", anon, "login", [], { withShell: false });
  await anon.goto("/__no-such-page__");
  await expect(anon.getByRole("heading", { name: "页面不存在" })).toBeVisible();
  await check("404（未登录）", anon, "notFound", [], { withShell: false });

  // ---- 用户管理 ----
  await open("/users");
  await expect(page.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();
  const list = await check("用户列表（第 1 页）", page, "usersList");
  // 造数保证用户数超过一页（20）：分页只有「下一页」；列表页的分页链接形状固定
  expect(list.pager.some((p) => /^link "下一页" -> \/users\?page=2$/.test(p)), "用户列表应有指向第 2 页的链接").toBe(true);
  await open("/users?page=2");
  const second = await check("用户列表（第 2 页）", page, "usersList");
  expect(second.pager.some((p) => p === 'link "上一页" -> /users'), "用户列表第 2 页应有回到第 1 页的链接").toBe(true);

  await open("/users?q=%E5%8E%9F%E5%9E%8B%E6%A0%B8%E5%AF%B9%E6%97%A0%E6%AD%A4%E4%BA%BA");
  await expect(page.getByText(/没有找到匹配/)).toBeVisible();
  await check("用户列表（搜索无结果）", page, "usersEmpty");

  await open(`/users/${USERS_SEED.multi.id}`);
  await expect(page.getByRole("heading", { level: 1, name: USERS_SEED.multi.name })).toBeVisible();
  await check("用户详情（多账号用户）", page, "userDetailActive");
  await open(`/users/${USERS_SEED.none.id}`);
  await expect(page.getByText("该用户暂无关联账号")).toBeVisible();
  await check("用户详情（无账号用户）", page, "userDetailActive");
  await open(`/users/${USERS_SEED.disabled.id}`);
  await expect(page.getByRole("button", { name: "启用用户" })).toBeVisible();
  await check("用户详情（已停用用户）", page, "userDetailDisabled");
  await open(`/users/${adminId}`);
  await expect(page.getByRole("button", { name: "停用用户" })).toBeDisabled();
  await check("用户详情（自己）", page, "userDetailSelf");
  await open(`/users/${withSessionId}`);
  await expect(page.getByRole("heading", { level: 1, name: withSession.userName })).toBeVisible();
  await check("用户详情（有会话的用户）", page, "userDetailActive");
  await open(`/users/${USERS_SEED.missing}`);
  await expect(page.getByRole("heading", { level: 1, name: "用户不存在" })).toBeVisible();
  await check("用户详情（用户不存在）", page, "userNotFound");
  await open("/users/new");
  await expect(page.getByRole("heading", { level: 1, name: "新建用户" })).toBeVisible();
  await check("新建用户", page, "newUser");

  // ---- 会话管理 ----
  await open("/sessions");
  await expect(page.getByRole("heading", { level: 1, name: "会话管理" })).toBeVisible();
  const online = await check("在线用户列表", page, "sessionsList");
  // 在线用户的分页只可能是列表页的分页链接（页数取决于栈里累积的会话数）
  for (const pager of online.pager) expect(pager).toMatch(PAGER);
  await open(`/sessions/${withSessionId}`);
  await expect(page.getByRole("list", { name: "会话列表" })).toBeVisible();
  await check("用户会话详情（有会话）", page, "sessionDetail");
  await open(`/sessions/${withoutSessionId}`);
  await expect(page.getByText("该用户当前没有在线会话")).toBeVisible();
  await check("用户会话详情（无在线会话）", page, "sessionDetailEmpty");
  // 自己的当前会话：用只有一个账号、一个会话的「原型核对」用户（ADMIN 的用户在共享栈上可能还有别的账号的会话，不稳定）
  const own = await ctxs.signIn(browser, baseURL, page, ownUser.accountName);
  await own.goto(`/sessions/${ownUserId}`);
  await expect(own.getByText("当前会话", { exact: true }).first()).toBeVisible();
  await check("用户会话详情（自己的当前会话）", own, "sessionDetailOwn", [], { userName: ownUser.userName });
  await open(`/sessions/${USERS_SEED.missing}`);
  await expect(page.getByRole("heading", { level: 1, name: "用户不存在" })).toBeVisible();
  await check("用户会话详情（用户不存在）", page, "sessionUserNotFound");

  // ---- 修改密码、404 ----
  await open("/account/password");
  await expect(page.getByRole("heading", { level: 1, name: "修改密码" })).toBeVisible();
  await check("修改密码", page, "password");
  await page.goto("/__no-such-page__");
  await expect(page.getByRole("heading", { name: "页面不存在" })).toBeVisible();
  await check("404（已登录）", page, "notFound", [], { withShell: false });

  // ---- 浮层：用户菜单、导航抽屉、确认框 ----
  await open("/users");
  const menu = await openUserMenu(page, ADMIN.name);
  // 用户菜单是非模态的（见 user-menu.tsx）：背景页面仍在无障碍树里，所以是页面控件 + 菜单项
  await check("顶栏用户菜单", page, "userMenu", EXPECTED.usersList);
  await expect(menu).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(menu).toBeHidden();

  if (isPhone(page)) {
    await openNav(page);
    await check("手机导航抽屉", page, "drawer", [], { withShell: false });
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toHaveCount(0);
  }

  await open(`/users/${withSessionId}`);
  await page.getByRole("button", { name: "停用用户" }).click();
  const disable = page.getByRole("alertdialog");
  await expect(disable).toBeVisible();
  await settle(disable);
  await check("停用确认框", page, "disableDialog", [], { withShell: false });
  await disable.getByRole("button", { name: "取消" }).click();
  await expect(disable).toBeHidden();

  await open(`/users/${USERS_SEED.disabled.id}`);
  await page.getByRole("button", { name: "启用用户" }).click();
  const enable = page.getByRole("alertdialog");
  await expect(enable).toBeVisible();
  await settle(enable);
  await check("启用确认框", page, "enableDialog", [], { withShell: false });
  await enable.getByRole("button", { name: "取消" }).click();
  await expect(enable).toBeHidden();

  await open(`/sessions/${withSessionId}`);
  await page.getByRole("button", { name: "强制下线" }).click();
  const terminate = page.getByRole("alertdialog");
  await expect(terminate).toBeVisible();
  await settle(terminate);
  await check("强制下线确认框", page, "terminateDialog", [], { withShell: false });
  await terminate.getByRole("button", { name: "取消" }).click();
  await expect(terminate).toBeHidden();

  // 确认真的遍历了：页面 + 浮层都有（防止日后有人删掉某一步而不自知）
  expect(visited).toHaveLength(isPhone(page) ? 25 : 24);
});

// 自检：禁止项检查本身能抓到范围外的入口（不是摆设）
test("禁止项检查能识别各类范围外入口（自检）", () => {
  const entries = [
    'link "编辑用户名" -> /users/x',
    'button "删除用户"',
    'button "重置密码"',
    'link "忘记密码？" -> /forgot',
    'button "绑定账号"',
    'button "批量下线"',
    'button "一键下线"',
    'button "全部下线"',
    'link "x" -> /users/abc/edit',
    'link "x" -> /account/reset-password',
    'link "x" -> /sessions/all',
  ];
  for (const entry of entries) {
    const url = entry.split(" -> ")[1] ?? "";
    expect(FORBIDDEN_NAME.test(entry) || FORBIDDEN_URL.test(url), entry).toBe(true);
  }
  // 范围内的控件不被误伤
  for (const ok of ['button "修改密码"', 'menuitem "退出登录"', 'button "强制下线"', 'button "停用用户"', 'button "启用用户"', 'link "新建用户" -> /users/new']) {
    expect(FORBIDDEN_NAME.test(ok), ok).toBe(false);
  }
});
