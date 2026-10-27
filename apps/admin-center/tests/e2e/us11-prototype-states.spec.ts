import { type BrowserContext, type Locator, type Page } from "@playwright/test";
import { ADMIN, expect, login, seedUsers, test, userCountNamed, userIdOfAccount, USERS_SEED } from "./support/fixtures";
import { Contexts, createUserViaUi, expectShellMode, mainNav, openNav, openUserMenu, PASSWORD, runId, settle, widthOf } from "./support/responsive";
import { startService, stopService, uamCli } from "./support/stack";

/**
 * US11：界面实现与原型一致（FR-041、SC-015；quickstart S18）。
 * 对照 `docs/system-design/admin-center/prototype/README.md` 的「覆盖的页面与状态」表，逐行在**真实界面**里复现原型的关键状态；
 * 每个 describe 的标题标出它对应的 README 行，用例里的断言再标出具体的关键状态。
 *
 * 前提：栈已按 devops/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）。
 *
 * 复现不了的状态（诚实说明，不用假装）：
 * - 「空列表」中的「还没有用户」与「当前没有在线用户」：共享栈里永远有 init-admin 造出的初始用户，而且能看到页面的人本身就在线，
 *   这两个空态在真实栈上出不来（会话每次校验都会把自己写回在线索引，直接清 Redis 也骗不过去）。它们由单元 / 组件测试覆盖：
 *   `tests/unit/components/users/user-list.test.tsx`（「还没有用户」）、`tests/unit/components/sessions/online-user-list.test.tsx`（「当前没有在线用户」），
 *   页面级由 `tests/unit/app/users-pages.test.tsx`、`tests/unit/app/sessions-pages.test.tsx` 覆盖。这里能真实复现的空列表是：搜索无结果、用户没有在线会话、全部下线后的空态。
 * - 「不能停用最后一个正常用户」：真实栈上只会在并发操作时出现（原型也写明「仅为展示设计」）；由 `tests/unit/app/user-status-actions.test.ts` 与
 *   `tests/unit/components/users/status-actions.test.tsx` 覆盖。
 * - 「UAM 因服务密钥配置错误拒绝 Admin Center」同样呈现为「服务暂不可用」：该变体放在 US12（`us12-rotation.spec.ts`，T177）里测，这里只测停掉 UAM。
 *
 * 造数：用例经「新建用户」界面（US4）建全新的用户；名字都以「原型核对」开头并带本次运行与视口的后缀，
 * 刻意不含其它规格造数用户的子串（多账号用户、无账号用户、已停用用户、分页用户、在线者、离线者、建档员、停权对象、强退对象、改密者、
 * 终结验证、响应式、甲号、乙号、丙号）。需要「无账号」「已停用」的用户时用 US2 的固定造数（`seedUsers`，只读，不修改）。
 * 一个账号只有一个会话：每个账号只在一个浏览器上下文里登录；不碰 ADMIN 的密码。
 */

const COOKIE_NAME = process.env.E2E_SESSION_COOKIE_NAME ?? "asid"; // devops/local/e2e.env 的 SESSION_COOKIE_NAME

const ctxs = new Contexts();

/** 本组规格造的名字：用户名 `原型核对…`、账号名 `e2e.p11.…`（≤ 32 个字符且只含 `[A-Za-z0-9_.-]`）。 */
function names(page: Page, tag: string): { userName: string; accountName: string } {
  const suffix = `${runId}${widthOf(page)}${tag}`;
  return { userName: `原型核对${suffix}`, accountName: `e2e.p11.${suffix}` };
}

async function loginAdmin(page: Page): Promise<void> {
  await login(page);
  await expect(page).toHaveURL(/\/users$/);
}

const sessionCookie = async (context: BrowserContext) => (await context.cookies()).find((c) => c.name === COOKIE_NAME);

/** 表单字段（按标签精确匹配）。 */
const field = (page: Page, label: string) => page.getByLabel(label, { exact: true });

/** 页面级提示（Next 自带的路由播报器也是 role=alert，所以按文案过滤）。 */
const alertWith = (page: Page, text: string | RegExp) => page.getByRole("alert").filter({ hasText: text });
const statusWith = (page: Page, text: string | RegExp) => page.getByRole("status").filter({ hasText: text });

const sessionCards = (page: Page): Locator => page.getByRole("list", { name: "会话列表" }).getByRole("listitem");

test.beforeAll(() => {
  seedUsers();
});

test.afterEach(async () => {
  await ctxs.closeAll();
});

// =====================================================================================================================
// README 行「登录页」：必填提示、统一失败提示、限流提示、会话结束 / 被下线提示
// =====================================================================================================================

test.describe("README 行：登录页", () => {
  test("必填提示；未登录访问受保护页面被带回登录页（带 next）", async ({ page }) => {
    // 登录页 · 必填提示
    await page.goto("/login");
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page.getByText("请填写账号名")).toBeVisible();
    await expect(page.getByText("请填写密码")).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
    // 登录页 · 受保护页面访问会被带回登录页
    await page.goto("/sessions");
    await expect(page).toHaveURL(/\/login\?next=%2Fsessions$/);
  });

  test("统一失败提示（错密码 = 不存在的账号名，不回填密码）与限流提示", async ({ browser, baseURL, page }) => {
    const p = await ctxs.open(browser, baseURL, page); // 独立来源地址，限流计数不串到别的用例
    const failure = (text: string | RegExp) => alertWith(p, text);

    // 登录页 · 统一失败提示
    await login(p, ADMIN.account, "definitely-wrong-1");
    await expect(failure("账号名或密码错误")).toBeVisible();
    const wrongPassword = await failure("账号名或密码错误").innerText();
    await expect(p.getByLabel("密码")).toHaveValue("");
    await login(p, "no.such.account.p11", "definitely-wrong-1");
    await expect(failure("账号名或密码错误")).toBeVisible();
    expect(await failure("账号名或密码错误").innerText()).toBe(wrongPassword);

    // 登录页 · 限流提示：同一来源对同一账号连错 5 次（上面已 1 次），第 6 次即使密码正确也被拒
    for (let i = 0; i < 4; i++) {
      await login(p, ADMIN.account, `definitely-wrong-${i + 2}`);
      await expect(failure("账号名或密码错误")).toBeVisible();
    }
    await login(p, ADMIN.account, ADMIN.password);
    await expect(failure(/尝试过于频繁，请 \d+ 秒后再试/)).toBeVisible();
    await expect(p).toHaveURL(/\/login/);
    expect(await sessionCookie(p.context())).toBeUndefined();
  });

  test("会话结束提示（被同一账号的新登录取代）与被强制下线者看到的提示", async ({ browser, baseURL, page }) => {
    await loginAdmin(page);
    const v = names(page, "se");
    const victimId = await createUserViaUi(page, v);

    // 登录页 · 会话结束提示：同一账号在另一个浏览器再次登录，第一个浏览器的下一次请求被带回登录页
    const first = await ctxs.signIn(browser, baseURL, page, v.accountName);
    const second = await ctxs.signIn(browser, baseURL, page, v.accountName);
    await first.goto("/users");
    await expect(first).toHaveURL(/\/login\?.*reason=session_ended/);
    await expect(statusWith(first, "会话已结束，请重新登录。")).toBeVisible();

    // 登录页 · 被强制下线提示：运维在会话页把第二个会话强制下线（真实界面）
    await page.goto(`/sessions/${victimId}`);
    await sessionCards(page).first().getByRole("button", { name: "强制下线" }).click();
    await page.getByRole("alertdialog", { name: "强制下线该会话？" }).getByRole("button", { name: "强制下线", exact: true }).click();
    await expect(statusWith(page, "会话已强制下线。")).toBeVisible();
    await second.goto("/users");
    await expect(second).toHaveURL(/\/login\?.*reason=session_terminated/);
    // 被管理员强制下线有专门的提示（UAM 在 verify 的 401 上带 reason=terminated）；上面被取代的是通用提示，两者互不串
    await expect(statusWith(second, "你的会话已被管理员强制下线")).toBeVisible();
    await expect(second.getByText("会话已结束，请重新登录。")).toHaveCount(0);
  });
});

// =====================================================================================================================
// README 行：导航外壳（桌面 / 平板 / 手机）
// =====================================================================================================================

test.describe("README 行：导航外壳", () => {
  test("当前视口的外壳形态；两个导航入口；用户菜单含「修改密码」「退出登录」；面包屑", async ({ page }) => {
    await loginAdmin(page);
    await expectShellMode(page, "用户列表"); // 桌面侧边栏展开 / 平板图标条 / 手机抽屉（汉堡按钮）

    const nav = await openNav(page);
    await expect(nav.getByRole("link")).toHaveText(["用户管理", "会话管理"]);
    if (widthOf(page) < 768) {
      await page.keyboard.press("Escape"); // 手机：Esc 关闭抽屉并回到汉堡按钮
      await expect(page.getByRole("dialog")).toHaveCount(0);
      await expect(page.getByRole("button", { name: "打开导航菜单" })).toBeFocused();
    }

    const menu = await openUserMenu(page, ADMIN.name);
    await expect(menu.getByRole("menuitem")).toHaveText(["修改密码", "退出登录"]);
    await page.keyboard.press("Escape");
    await expect(menu).toBeHidden();

    await page.goto("/users/new");
    const crumbs = page.getByRole("navigation", { name: "面包屑" });
    await expect(crumbs.getByRole("link", { name: "用户管理" })).toBeVisible();
    await expect(crumbs).toContainText("新建用户");
    // 桌面与平板的导航固定在页面里；手机的导航只在抽屉里
    if (widthOf(page) >= 768) await expect(mainNav(page)).toBeVisible();
  });
});

// =====================================================================================================================
// README 行：User Manager 列表
// =====================================================================================================================

test.describe("README 行：User Manager 列表", () => {
  test("空列表：搜索无结果的空态与「清除搜索」（「还没有用户」见文件头说明，由单元测试覆盖）", async ({ page }) => {
    await loginAdmin(page);
    const term = `原型核对无此人${runId}${widthOf(page)}`;
    await page.getByRole("searchbox", { name: "搜索用户名或账号名" }).fill(term);
    await page.getByRole("button", { name: "搜索" }).click();
    await expect(page).toHaveURL(/\/users\?q=/);
    await expect(page.getByText(`没有找到匹配「${term}」的用户`)).toBeVisible();
    await expect(page.getByText("换个关键词试试。")).toBeVisible();
    await expect(page.getByRole("table", { name: "用户列表" })).toHaveCount(0);
    await expect(page.getByRole("navigation", { name: "分页" })).toHaveCount(0);

    await page.getByRole("link", { name: "清除搜索" }).click();
    await expect(page).toHaveURL(/\/users$/);
    // 列表恢复；表格（桌面 / 平板）或卡片（手机）里有状态徽标与关联账号数
    const list = widthOf(page) < 768 ? page.getByRole("list", { name: "用户卡片" }) : page.getByRole("table", { name: "用户列表" });
    await expect(list).toBeVisible();
    await expect(list.getByText("正常").first()).toBeVisible();
  });
});

// =====================================================================================================================
// README 行：新建用户表单
// =====================================================================================================================

test.describe("README 行：新建用户表单", () => {
  test("字段级校验（必填、账号名规则、密码强度）；失败后保留用户名与账号名、不回填密码", async ({ page }) => {
    await loginAdmin(page);
    await page.goto("/users/new");
    // 新建用户表单 · 必填
    await page.getByRole("button", { name: /^创建/ }).click();
    await expect(page.getByText("请填写用户名")).toBeVisible();
    await expect(page.getByText("请填写账号名")).toBeVisible();
    await expect(page.getByText("请填写初始密码")).toBeVisible();

    // 新建用户表单 · 账号名规则与密码强度；已填内容保留，密码框清空
    await field(page, "用户名").fill("原型核对校验");
    await field(page, "账号名").fill("ab");
    await field(page, "初始密码").fill("short1");
    await page.getByRole("button", { name: /^创建/ }).click();
    await expect(field(page, "账号名")).toHaveAccessibleDescription("账号名为 3–32 个字母、数字或 _ . -");
    await expect(field(page, "初始密码")).toHaveAccessibleDescription("密码至少 10 位，且同时包含字母和数字");
    await expect(field(page, "用户名")).toHaveValue("原型核对校验");
    await expect(field(page, "账号名")).toHaveValue("ab");
    await expect(field(page, "初始密码")).toHaveValue("");
    await expect(field(page, "账号名")).toHaveAttribute("aria-invalid", "true");
  });

  test("账号名冲突（大小写不敏感）：字段旁提示，保留已填内容（密码除外），不建数据", async ({ page }) => {
    await loginAdmin(page);
    const existing = names(page, "cf");
    await createUserViaUi(page, existing);
    const second = names(page, "cg");

    await page.goto("/users/new");
    await field(page, "用户名").fill(second.userName);
    await field(page, "账号名").fill(existing.accountName.toUpperCase());
    await field(page, "初始密码").fill(PASSWORD);
    await page.getByRole("button", { name: /^创建/ }).click();
    // 新建用户表单 · 账号名冲突
    await expect(field(page, "账号名")).toHaveAccessibleDescription("账号名已被使用");
    await expect(field(page, "用户名")).toHaveValue(second.userName);
    await expect(field(page, "账号名")).toHaveValue(existing.accountName.toUpperCase());
    await expect(field(page, "初始密码")).toHaveValue("");
    await expect(page).toHaveURL(/\/users\/new$/);
    expect(userCountNamed(second.userName)).toBe(0);
  });
});

// =====================================================================================================================
// README 行：用户详情（含账号、停用 / 启用）
// =====================================================================================================================

test.describe("README 行：用户详情", () => {
  test("多个账号的用户：账号全部列出（原型 #/users/u2）", async ({ page }) => {
    await loginAdmin(page);
    await page.goto(`/users/${USERS_SEED.multi.id}`);
    await expect(page.getByRole("heading", { level: 1, name: USERS_SEED.multi.name })).toBeVisible();
    await expect(page.getByRole("region", { name: `关联账号（${USERS_SEED.multi.accounts.length}）` })).toBeVisible();
    for (const account of USERS_SEED.multi.accounts) await expect(page.getByText(account, { exact: true }).locator("visible=true")).toBeVisible();
  });

  test("用户无账号：空状态「该用户暂无关联账号」（原型 #/users/u5）", async ({ page }) => {
    await loginAdmin(page);
    await page.goto(`/users/${USERS_SEED.none.id}`);
    await expect(page.getByRole("heading", { level: 1, name: USERS_SEED.none.name })).toBeVisible();
    await expect(page.getByRole("region", { name: "关联账号（0）" })).toBeVisible();
    await expect(page.getByText("该用户暂无关联账号")).toBeVisible();
    await expect(page.getByText("该用户目前无法登录。")).toBeVisible();
    await expect(page.getByRole("table", { name: "关联账号" })).toHaveCount(0);
  });

  test("已停用的用户：状态与说明；「启用用户」的确认框，取消后仍是已停用（原型 #/users/u4）", async ({ page }) => {
    await loginAdmin(page);
    await page.goto(`/users/${USERS_SEED.disabled.id}`);
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("已停用");
    await expect(statusWith(page, "该用户已停用：其名下所有账号都无法登录，也没有在线会话。")).toBeVisible();
    await expect(page.getByRole("button", { name: "停用用户" })).toHaveCount(0);

    // 停用 / 启用的确认对话框（启用）：取消，不改状态（共享造数不被修改）
    await page.getByRole("button", { name: "启用用户" }).click();
    const dialog = page.getByRole("alertdialog", { name: `启用用户「${USERS_SEED.disabled.name}」？` });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText("启用后，该用户的账号可以重新登录。此前已结束的会话不会恢复。");
    await settle(dialog);
    await dialog.getByRole("button", { name: "取消" }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("已停用");
  });

  test("用户不存在（不存在的标识与格式非法的标识，原型 #/users/nope）", async ({ page }) => {
    await loginAdmin(page);
    for (const id of [USERS_SEED.missing, "nope"]) {
      await page.goto(`/users/${id}`);
      await expect(page.getByRole("heading", { level: 1, name: "用户不存在" })).toBeVisible();
    }
    await page.getByRole("link", { name: "返回用户列表" }).click();
    await expect(page).toHaveURL(/\/users$/);
  });

  test("停用的确认框：说明后果，Esc 与「取消」都不停用；停用自己时入口禁用并说明原因", async ({ page }) => {
    await loginAdmin(page);
    const target = names(page, "dc");
    const id = await createUserViaUi(page, target);

    // 停用与强制下线的确认 · 停用
    await page.goto(`/users/${id}`);
    const trigger = page.getByRole("button", { name: "停用用户" });
    await trigger.click();
    const dialog = page.getByRole("alertdialog", { name: `停用用户「${target.userName}」？` });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText("所有账号都无法登录");
    await expect(dialog).toContainText("所有在线会话会立即结束");
    await expect(dialog).toContainText("已结束的会话不会恢复");
    await settle(dialog);
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
    await expect(trigger).toBeFocused();
    await trigger.click();
    await settle(dialog);
    await dialog.getByRole("button", { name: "取消" }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("正常");

    // 不可停用 / 不可下线的禁用说明 · 不能停用自己
    await page.goto(`/users/${userIdOfAccount(ADMIN.account)}`);
    const self = page.getByRole("button", { name: "停用用户" });
    await expect(self).toBeDisabled();
    await expect(self).toHaveAccessibleDescription("不能停用当前登录的用户。");
    await expect(page.getByText("不能停用当前登录的用户。")).toBeVisible();
    await self.click({ force: true });
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
  });
});

// =====================================================================================================================
// README 行：Session Manager 在线用户列表
// =====================================================================================================================

test.describe("README 行：Session Manager 在线用户列表", () => {
  test("在线用户：用户名、有效会话数、最近活动；自己的行带「你」（「当前没有在线用户」见文件头说明，由单元测试覆盖）", async ({ page }) => {
    await loginAdmin(page);
    await page.goto("/sessions");
    await expect(page.getByRole("heading", { level: 1, name: "会话管理" })).toBeVisible();
    // 表格（桌面 / 平板，有「用户名」列头）与卡片（手机，用户名就是卡片标题）两份标记都在，CSS 只显示其中一份
    for (const label of widthOf(page) < 768 ? ["有效会话数", "最近活动"] : ["用户名", "有效会话数", "最近活动"]) {
      await expect(page.getByText(label, { exact: true }).locator("visible=true").first()).toBeVisible();
    }
    const adminId = userIdOfAccount(ADMIN.account);
    const own = page.locator(`a[href="/sessions/${adminId}"]`).locator("visible=true");
    await expect(own).toHaveText(ADMIN.name);
    await expect(page.getByText("你", { exact: true }).locator("visible=true")).toBeVisible();
    // 点用户名进入该用户的会话详情
    await own.click();
    await expect(page).toHaveURL(new RegExp(`/sessions/${adminId}$`));
  });
});

// =====================================================================================================================
// README 行：用户会话详情（含强制下线）
// =====================================================================================================================

test.describe("README 行：用户会话详情", () => {
  test("无在线会话：空状态与「返回在线用户」；用户不存在", async ({ page }) => {
    await loginAdmin(page);
    const idle = names(page, "ns");
    const id = await createUserViaUi(page, idle); // 建了账号但从没登录过
    await page.goto(`/sessions/${id}`);
    await expect(page.getByRole("heading", { level: 1, name: `${idle.userName} 的会话` })).toBeVisible();
    await expect(page.getByText("该用户当前没有在线会话")).toBeVisible();
    await expect(page.getByText("可能已退出、到期或被下线。")).toBeVisible();
    await expect(sessionCards(page)).toHaveCount(0);
    await page.getByRole("link", { name: "返回在线用户" }).click();
    await expect(page).toHaveURL(/\/sessions$/);

    await page.goto(`/sessions/${USERS_SEED.missing}`);
    await expect(page.getByRole("heading", { level: 1, name: "用户不存在" })).toBeVisible();
  });

  test("两个会话：账号及种类、登录 / 最近活动 / 到期时间、会话引用、不显示会话标识；强制下线的确认（取消与确认）；全部下线后的空态", async ({
    browser,
    baseURL,
    page,
  }) => {
    await loginAdmin(page);
    const v = names(page, "ss");
    const accountB = `${v.accountName}b`;
    const id = await createUserViaUi(page, v);
    uamCli(["add-account", "--user-id", id, "--account-name", accountB], `${PASSWORD}\n`);
    const a = await ctxs.signIn(browser, baseURL, page, v.accountName);
    const b = await ctxs.signIn(browser, baseURL, page, accountB);
    const secrets = [(await sessionCookie(a.context()))!.value, (await sessionCookie(b.context()))!.value];

    await page.goto(`/sessions/${id}`);
    await expect(page.getByRole("heading", { level: 1, name: `${v.userName} 的会话` })).toBeVisible();
    await expect(sessionCards(page)).toHaveCount(2);
    const cardOf = (account: string) => sessionCards(page).filter({ has: page.getByText(account, { exact: true }) });
    for (const account of [v.accountName, accountB]) {
      const card = cardOf(account);
      await expect(card).toHaveCount(1);
      await expect(card).toContainText("账号名 / 密码");
      for (const label of ["登录时间", "最近活动", "到期时间", "会话引用"]) await expect(card.getByText(label, { exact: true })).toBeVisible();
      await expect(card).not.toContainText("当前会话"); // 这是别人的会话，不是运维自己的
    }
    // 会话引用（非机密）可见，机密的会话标识绝不出现在页面上
    const html = await page.content();
    for (const secret of secrets) expect(html).not.toContain(secret);

    // 停用与强制下线的确认 · 强制下线：取消不下线
    await cardOf(v.accountName).getByRole("button", { name: "强制下线" }).click();
    const dialog = page.getByRole("alertdialog", { name: "强制下线该会话？" });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText(`账号「${v.accountName}」的这个会话将立即结束`);
    await expect(dialog).toContainText("该用户的其他会话不受影响");
    await settle(dialog);
    await dialog.getByRole("button", { name: "取消" }).click();
    await expect(dialog).toBeHidden();
    await expect(sessionCards(page)).toHaveCount(2);

    // 确认：该会话消失，另一个不受影响
    await cardOf(v.accountName).getByRole("button", { name: "强制下线" }).click();
    await settle(dialog);
    await dialog.getByRole("button", { name: "强制下线", exact: true }).click();
    await expect(statusWith(page, "会话已强制下线。")).toBeVisible();
    await expect(sessionCards(page)).toHaveCount(1);
    await expect(cardOf(accountB)).toHaveCount(1);

    // 全部下线后的空状态
    await cardOf(accountB).getByRole("button", { name: "强制下线" }).click();
    await settle(dialog);
    await dialog.getByRole("button", { name: "强制下线", exact: true }).click();
    await expect(page.getByText("该用户当前没有在线会话")).toBeVisible();
    await expect(page.getByRole("link", { name: "返回在线用户" })).toBeVisible();
    await expect(sessionCards(page)).toHaveCount(0);
  });

  test("不可下线：自己的当前会话标注「当前会话」，入口禁用并说明，没有任何弹窗", async ({ page }) => {
    await loginAdmin(page);
    await page.goto(`/sessions/${userIdOfAccount(ADMIN.account)}`);
    const own = sessionCards(page).filter({ hasText: "当前会话" });
    await expect(own).toHaveCount(1);
    const button = own.getByRole("button", { name: "强制下线" });
    await expect(button).toBeDisabled();
    await expect(button).toHaveAccessibleDescription("这是你当前使用的会话，请改用顶栏用户菜单中的「退出登录」。");
    await expect(own.getByText("这是你当前使用的会话，请改用顶栏用户菜单中的「退出登录」。")).toBeVisible();
    await button.click({ force: true });
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    await page.reload();
    await expect(sessionCards(page).filter({ hasText: "当前会话" })).toHaveCount(1); // 会话仍然有效
  });
});

// =====================================================================================================================
// README 行：修改密码
// =====================================================================================================================

test.describe("README 行：修改密码", () => {
  test("各类校验（必填、强度、与当前相同、两次不一致、当前密码不正确）与成功提示；字段始终清空；当前会话保持有效", async ({ browser, baseURL, page }) => {
    await loginAdmin(page);
    const v = names(page, "pw");
    await createUserViaUi(page, v);
    const u = await ctxs.signIn(browser, baseURL, page, v.accountName);
    await u.goto("/account/password");

    const submit = u.getByRole("button", { name: "修改密码" });
    const fill = async (current: string, next: string, confirm: string) => {
      await field(u, "当前密码").fill(current);
      await field(u, "新密码").fill(next);
      await field(u, "确认新密码").fill(confirm);
      await submit.click();
    };
    const NEXT = "E2e-Proto-New-1";

    // 必填
    await submit.click();
    await expect(u.getByText("请填写当前密码")).toBeVisible();
    await expect(u.getByText("请填写新密码")).toBeVisible();
    await expect(u.getByText("请再次输入新密码")).toBeVisible();
    // 强度
    await fill(PASSWORD, "short1", "short1");
    await expect(field(u, "新密码")).toHaveAccessibleDescription("新密码至少 10 位，且同时包含字母和数字");
    // 与当前密码相同
    await fill(PASSWORD, PASSWORD, PASSWORD);
    await expect(field(u, "新密码")).toHaveAccessibleDescription("新密码不能与当前密码相同");
    // 两次输入不一致
    await fill(PASSWORD, NEXT, `${NEXT}x`);
    await expect(field(u, "确认新密码")).toHaveAccessibleDescription("两次输入的新密码不一致");
    // 当前密码不正确
    await fill("Wrong-Current-1", NEXT, NEXT);
    await expect(field(u, "当前密码")).toHaveAccessibleDescription("当前密码不正确");
    for (const label of ["当前密码", "新密码", "确认新密码"]) await expect(field(u, label)).toHaveValue("");

    // 成功提示：当前会话保持有效、字段清空
    await fill(PASSWORD, NEXT, NEXT);
    await expect(statusWith(u, "密码已修改，当前会话保持有效。")).toBeVisible();
    for (const label of ["当前密码", "新密码", "确认新密码"]) await expect(field(u, label)).toHaveValue("");
    await u.goto("/users");
    await expect(u).toHaveURL(/\/users$/);
  });
});

// =====================================================================================================================
// README 行：关键状态 —— UAM 暂不可用（保留登录状态；写操作提示「操作未生效」，绝不显示成功）
// =====================================================================================================================

test.describe("README 行：关键状态 · UAM 暂不可用", () => {
  test("停掉 UAM：服务暂不可用横幅、保留 cookie 与登录状态、写操作提示「操作未生效」且没有生效；UAM 恢复后无需重新登录", async ({ browser, baseURL, page }) => {
    test.setTimeout(300_000);
    await loginAdmin(page);
    const op = names(page, "dn"); // 操作人：独立用户，下面的写操作都用他的会话，不碰 ADMIN
    const target = names(page, "dt"); // 被「停用」的对象（不会真的被停用）
    const newUser = names(page, "dx"); // 被「新建」的对象（不会真的被创建）
    await createUserViaUi(page, op);
    const targetId = await createUserViaUi(page, target);
    const opPage = await ctxs.signIn(browser, baseURL, page, op.accountName);
    const cookieBefore = (await sessionCookie(opPage.context()))!.value;

    // 停掉 UAM 之前，先把三个写入口摆好（UAM 不可用时受保护页面不渲染表单，所以只能先打开再停）
    await opPage.goto("/users/new");
    await field(opPage, "用户名").fill(newUser.userName);
    await field(opPage, "账号名").fill(newUser.accountName);
    await field(opPage, "初始密码").fill(PASSWORD);

    const detailPage = await opPage.context().newPage();
    await detailPage.goto(`/users/${targetId}`);
    await detailPage.getByRole("button", { name: "停用用户" }).click();
    const disableDialog = detailPage.getByRole("alertdialog", { name: `停用用户「${target.userName}」？` });
    await expect(disableDialog).toBeVisible();
    await settle(disableDialog);

    const passwordPage = await opPage.context().newPage();
    await passwordPage.goto("/account/password");
    await field(passwordPage, "当前密码").fill(PASSWORD);
    await field(passwordPage, "新密码").fill("E2e-Never-Applied-9");
    await field(passwordPage, "确认新密码").fill("E2e-Never-Applied-9");

    stopService("uam");
    try {
      // 写操作：「操作未生效」，绝不显示成功
      await opPage.getByRole("button", { name: /^创建/ }).click();
      await expect(alertWith(opPage, "操作未生效")).toBeVisible({ timeout: 20_000 });
      await expect(opPage).toHaveURL(/\/users\/new$/);
      await expect(opPage.getByText("用户已创建")).toHaveCount(0);
      await expect(field(opPage, "初始密码")).toHaveValue("");

      await disableDialog.getByRole("button", { name: "停用", exact: true }).click();
      await expect(alertWith(detailPage, "操作未生效")).toBeVisible({ timeout: 20_000 });
      await expect(detailPage.getByText(/已停用，其会话已结束/)).toHaveCount(0);

      await passwordPage.getByRole("button", { name: "修改密码" }).click();
      await expect(alertWith(passwordPage, "操作未生效")).toBeVisible({ timeout: 20_000 });
      await expect(passwordPage.getByText("密码已修改")).toHaveCount(0);

      // 读：横幅「服务暂不可用」+ 说明登录状态已保留；不跳登录页、cookie 还在、没有登录后的用户菜单
      for (const path of ["/users", "/sessions", `/users/${targetId}`, `/sessions/${targetId}`, "/account/password"]) {
        await opPage.goto(path);
        await expect(statusWith(opPage, "服务暂不可用，请稍后重试。")).toBeVisible({ timeout: 20_000 });
        await expect(opPage.getByText("你的登录状态已保留，服务恢复后无需重新登录。")).toBeVisible();
        await expect(opPage.getByText("写操作此时会提示「操作未生效」。")).toBeVisible();
        await expect(opPage).toHaveURL(new RegExp(`${path}$`));
        expect((await sessionCookie(opPage.context()))?.value).toBe(cookieBefore);
      }

      // 登录页在 UAM 不可用时：提示服务暂不可用（不是「账号名或密码错误」），没有会话
      const anon = await ctxs.open(browser, baseURL, page);
      await login(anon, ADMIN.account, ADMIN.password);
      await expect(alertWith(anon, "服务暂不可用，请稍后重试")).toBeVisible({ timeout: 20_000 });
      await expect(anon).toHaveURL(/\/login/);
      expect(await sessionCookie(anon.context())).toBeUndefined();
    } finally {
      startService("uam"); // 无论断言是否失败，都要把 UAM 拉起来（栈共享）
    }

    // 恢复：等 Admin Center 重新看到 UAM；原会话仍有效（无需重新登录），cookie 没变
    await expect(async () => {
      await opPage.goto("/users");
      await expect(opPage.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 90_000 });
    await expect(opPage).toHaveURL(/\/users$/);
    expect((await sessionCookie(opPage.context()))?.value).toBe(cookieBefore);

    // 刚才的三个写操作都没有生效：用户没建、对象仍是「正常」、旧密码仍可登录
    expect(userCountNamed(newUser.userName)).toBe(0);
    await opPage.goto(`/users/${targetId}`);
    await expect(opPage.getByRole("region", { name: "基本信息" })).toContainText("正常");
    const again = await ctxs.signIn(browser, baseURL, page, op.accountName);
    await expect(again).toHaveURL(/\/users$/);
  });
});
