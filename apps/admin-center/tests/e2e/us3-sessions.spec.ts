import AxeBuilder from "@axe-core/playwright";
import { type Browser, type BrowserContext, type Locator, type Page } from "@playwright/test";
import {
  ADMIN,
  expect,
  liveSessions,
  login,
  seedSessionUsers,
  SESSIONS_SEED,
  SOURCE_HEADERS,
  test,
  userIdOfAccount,
} from "./support/fixtures";

/**
 * US3：在 Session Manager 中查看在线用户及其会话（quickstart S7；FR-014、FR-028 ~ FR-032；SC-002、SC-003）。
 * 前提：栈已按 infra/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）；UAM 已实现 US3 的两个读接口。
 * 造数：两个非 ADMIN 用户（一个有两个账号、一个有一个账号），各账号在独立的浏览器上下文里登录（账号与会话一一对应）。
 * 对账：Redis 的 `uam:online` 是在线用户的事实来源（见 liveSessions）；页面上的集合与会话数必须与它一致。
 * 手机（< 768）上列表以卡片呈现，所以定位器按视口宽度切换；会话卡片各视口一致。
 */

const { multi, single, idle, password } = SESSIONS_SEED;
const COOKIE_NAME = process.env.E2E_SESSION_COOKIE_NAME ?? "asid"; // infra/local/e2e.env 的 SESSION_COOKIE_NAME

const isNarrow = (page: Page) => (page.viewportSize()?.width ?? 1280) < 768;

/** 在线用户列表里的每个用户：桌面是表格行，手机是卡片。 */
const onlineItems = (page: Page): Locator =>
  isNarrow(page)
    ? page.getByRole("list", { name: "在线用户卡片" }).getByRole("listitem")
    : page.getByRole("table", { name: "在线用户列表" }).locator("tbody tr");

const onlineItem = (page: Page, name: string) => onlineItems(page).filter({ hasText: name });

/** 某个在线用户条目里的「有效会话数」。 */
const sessionCountOf = (item: Locator, page: Page) => (isNarrow(page) ? item.locator("dd").nth(0) : item.getByRole("cell").nth(1));

const sessionCards = (page: Page) => page.getByRole("list", { name: "会话列表" }).getByRole("listitem");
const pager = (page: Page) => page.getByRole("navigation", { name: "分页" });

/** 走完所有页，收集在线用户（标识取自链接、会话数取自条目）。 */
async function listedOnlineUsers(page: Page): Promise<Map<string, number>> {
  const listed = new Map<string, number>();
  await page.goto("/sessions");
  for (;;) {
    await expect(onlineItems(page).first()).toBeVisible();
    for (const item of await onlineItems(page).all()) {
      const href = await item.getByRole("link").getAttribute("href");
      listed.set(href!.replace("/sessions/", ""), Number(await sessionCountOf(item, page).innerText()));
    }
    const next = pager(page).getByRole("link", { name: "下一页" });
    if ((await next.count()) === 0) return listed;
    await next.click();
  }
}

/** Redis 里实际有效的会话，按用户汇总会话数。 */
function actualOnlineUsers(): Map<string, number> {
  const actual = new Map<string, number>();
  for (const { userId } of liveSessions()) actual.set(userId, (actual.get(userId) ?? 0) + 1);
  return actual;
}

const sorted = (m: Map<string, number>) => [...m.entries()].sort(([a], [b]) => a.localeCompare(b));

const noHorizontalScroll = (page: Page) =>
  page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);

const seriousViolations = async (page: Page) =>
  (await new AxeBuilder({ page }).analyze()).violations.filter((v) => v.impact === "serious" || v.impact === "critical");

interface UserSession {
  context: BrowserContext;
  page: Page;
}

const opened: BrowserContext[] = [];

/** 在新的浏览器上下文里用某个账号登录（新上下文不继承项目配置：baseURL、视口、来源地址头都要显式带上）。 */
async function loginInNewContext(browser: Browser, baseURL: string | undefined, like: Page, account: string): Promise<UserSession> {
  const context = await browser.newContext({
    baseURL,
    viewport: like.viewportSize() ?? undefined,
    extraHTTPHeaders: SOURCE_HEADERS,
  });
  opened.push(context);
  const page = await context.newPage();
  await login(page, account, password);
  await expect(page).toHaveURL(/\/users$/);
  return { context, page };
}

/** 让「多账号用户」的两个账号与「单账号用户」各开一个会话。 */
async function loginSeededUsers(browser: Browser, baseURL: string | undefined, like: Page) {
  const alpha = await loginInNewContext(browser, baseURL, like, multi.accounts[0]);
  const beta = await loginInNewContext(browser, baseURL, like, multi.accounts[1]);
  const solo = await loginInNewContext(browser, baseURL, like, single.account);
  return { alpha, beta, solo };
}

test.beforeAll(() => seedSessionUsers());

test.beforeEach(async ({ page }) => {
  await login(page);
  await expect(page).toHaveURL(/\/users$/);
});

test.afterEach(async () => {
  await Promise.all(opened.splice(0).map((context) => context.close()));
});

test.describe("S7：在线用户列表", () => {
  test("h1「会话管理」；列含用户名、有效会话数、最近活动；在线用户集合与会话数和 Redis 里实际有效的会话 100% 一致（SC-003）", async ({ page, browser, baseURL }) => {
    await loginSeededUsers(browser, baseURL, page);
    await page.goto("/sessions");
    await expect(page.getByRole("heading", { level: 1, name: "会话管理" })).toBeVisible();
    if (!isNarrow(page)) {
      await expect(page.getByRole("table", { name: "在线用户列表" }).getByRole("columnheader")).toHaveText(["用户名", "有效会话数", "最近活动"]);
    }
    await expect(onlineItem(page, multi.name)).toBeVisible();

    const listed = await listedOnlineUsers(page);
    const actual = actualOnlineUsers();
    expect(sorted(listed)).toEqual(sorted(actual));
    // 造出来的两个用户确实在线，且会话数符合账号数
    expect(listed.get(multi.id)).toBe(2);
    expect(listed.get(single.id)).toBe(1);
    expect(listed.has(userIdOfAccount(ADMIN.account))).toBe(true);
  });

  test("每行：用户名链接到会话详情、最近活动是相对时间；运维人员自己的行带「你」", async ({ page, browser, baseURL }) => {
    const { alpha } = await loginSeededUsers(browser, baseURL, page);
    await page.goto("/sessions");
    const mine = onlineItems(page).filter({ has: page.locator(`a[href="/sessions/${userIdOfAccount(ADMIN.account)}"]`) });
    await expect(mine).toHaveCount(1);
    await expect(mine).toContainText("你");
    await expect(mine.locator("time")).toHaveAttribute("datetime", /^\d{4}-\d{2}-\d{2}T/);
    await expect(mine.locator("time")).toHaveText(/刚刚|分钟前|小时前|天前/);
    await expect(onlineItem(page, multi.name)).not.toContainText("你");

    // 多账号用户自己登录进来看：「你」在他自己的行
    await alpha.page.goto("/sessions");
    await expect(onlineItem(alpha.page, multi.name)).toContainText("你");
    await expect(onlineItem(alpha.page, single.name)).not.toContainText("你");
  });

  test("没有整页横向滚动；axe 无严重违规；触控目标不小于 44px", async ({ page, browser, baseURL }) => {
    await loginSeededUsers(browser, baseURL, page);
    await page.goto("/sessions");
    await expect(onlineItem(page, multi.name)).toBeVisible();
    expect(await noHorizontalScroll(page)).toBeLessThanOrEqual(0);
    expect(await seriousViolations(page)).toEqual([]);
    const box = await onlineItem(page, multi.name).getByRole("link").boundingBox();
    expect(box!.height).toBeGreaterThanOrEqual(43.5);
  });
});

test.describe("S7：用户的会话详情", () => {
  test("多账号用户有两条会话，分别标明账号与种类、登录 / 最近活动 / 到期时间；没有会话标识，只有短会话引用", async ({ page, browser, baseURL }) => {
    const { alpha, beta } = await loginSeededUsers(browser, baseURL, page);
    await page.goto("/sessions");
    await onlineItem(page, multi.name).getByRole("link", { name: multi.name }).click();

    await expect(page).toHaveURL(new RegExp(`/sessions/${multi.id}$`));
    await expect(page.getByRole("heading", { level: 1, name: `${multi.name} 的会话` })).toBeVisible();
    await expect(sessionCards(page)).toHaveCount(2);
    for (const account of multi.accounts) {
      const card = sessionCards(page).filter({ has: page.getByText(account, { exact: true }) });
      await expect(card).toHaveCount(1);
      await expect(card).toContainText("账号名 / 密码");
      for (const label of ["登录时间", "最近活动", "到期时间", "会话引用"]) await expect(card).toContainText(label);
      await expect(card.locator("time")).toHaveCount(3);
      await expect(card.locator("time").first()).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
    }
    // 运维人员（ADMIN）不是这个用户：没有「当前会话」
    await expect(page.getByText("当前会话")).toHaveCount(0);

    // 页面里只有短会话引用，没有完整引用，更没有机密的会话标识（用两个上下文里真实的 cookie 值核对）
    const body = await page.locator("body").innerHTML();
    const refs = liveSessions().filter((s) => s.userId === multi.id).map((s) => s.ref);
    expect(refs).toHaveLength(2);
    for (const ref of refs) {
      expect(body).toContain(ref.slice(0, 8));
      expect(body).not.toContain(ref);
    }
    for (const { context } of [alpha, beta]) {
      const cookie = (await context.cookies()).find((c) => c.name === COOKIE_NAME);
      expect(cookie?.value).toBeTruthy();
      expect(body).not.toContain(cookie!.value);
    }
    expect(await noHorizontalScroll(page)).toBeLessThanOrEqual(0);
    expect(await seriousViolations(page)).toEqual([]);
  });

  test("「当前会话」只标在运维人员自己的会话上（文字标注）", async ({ page, browser, baseURL }) => {
    const { alpha } = await loginSeededUsers(browser, baseURL, page);

    // ADMIN 看自己：恰好一条「当前会话」，在 ADMIN 登录所用账号的卡片上（同一用户的其它账号会话不算）
    await page.goto(`/sessions/${userIdOfAccount(ADMIN.account)}`);
    await expect(page.getByText("当前会话", { exact: true })).toHaveCount(1);
    await expect(sessionCards(page).filter({ has: page.getByText(ADMIN.account, { exact: true }) })).toContainText("当前会话");

    // 多账号用户用 alpha 账号看自己：alpha 的卡片有，beta 的卡片没有
    await alpha.page.goto(`/sessions/${multi.id}`);
    await expect(sessionCards(alpha.page)).toHaveCount(2);
    await expect(alpha.page.getByText("当前会话", { exact: true })).toHaveCount(1);
    await expect(sessionCards(alpha.page).filter({ has: alpha.page.getByText(multi.accounts[0], { exact: true }) })).toContainText("当前会话");
    await expect(sessionCards(alpha.page).filter({ has: alpha.page.getByText(multi.accounts[1], { exact: true }) })).not.toContainText("当前会话");

    // 自己的当前会话不能被自己强制下线（US6）：入口禁用
    await expect(page.getByRole("button", { name: "强制下线" }).first()).toBeDisabled();
  });

  test("会话结束后刷新即消失：同一账号再次登录顶掉旧会话，旧引用不再显示、另一个账号的会话不受影响", async ({ page, browser, baseURL }) => {
    const { alpha } = await loginSeededUsers(browser, baseURL, page);
    await page.goto(`/sessions/${multi.id}`);
    await expect(sessionCards(page)).toHaveCount(2);
    const refOf = async (account: string) =>
      sessionCards(page).filter({ has: page.getByText(account, { exact: true }) }).locator("code").innerText();
    const [alphaBefore, betaBefore] = [await refOf(multi.accounts[0]), await refOf(multi.accounts[1])];
    expect(alphaBefore).not.toBe(betaBefore);

    await loginInNewContext(browser, baseURL, page, multi.accounts[0]); // 顶掉 alpha 的旧会话

    await page.reload();
    await expect(sessionCards(page)).toHaveCount(2); // 一个账号至多一个会话：仍是两条
    expect(await refOf(multi.accounts[0])).not.toBe(alphaBefore);
    expect(await refOf(multi.accounts[1])).toBe(betaBefore);
    expect(await page.locator("body").innerHTML()).not.toContain(alphaBefore);

    // 旧会话已失效：下一次请求被带到登录页
    await alpha.page.goto("/users");
    await expect(alpha.page).toHaveURL(/\/login/);
    // 在线集合依旧与 Redis 一致
    expect(sorted(await listedOnlineUsers(page))).toEqual(sorted(actualOnlineUsers()));
  });

  test("不存在的用户与格式非法的标识：显示「用户不存在」，可返回；不会显示成服务不可用", async ({ page }) => {
    for (const id of ["e2e00003-0000-4000-8000-0000000000ff", "not-a-uuid"]) {
      await page.goto(`/sessions/${id}`);
      await expect(page.getByRole("heading", { level: 1, name: "用户不存在" })).toBeVisible();
      await expect(page.getByText("该用户可能已被移除，或链接有误。")).toBeVisible();
      await expect(page.getByText("服务暂不可用")).toHaveCount(0);
    }
    await page.getByRole("link", { name: "返回用户列表" }).click();
    await expect(page).toHaveURL(/\/users$/);
  });

  test("没有会话的用户（有账号但从未登录）：空态与返回在线用户；也不在在线列表里", async ({ page }) => {
    await page.goto(`/sessions/${idle.id}`);
    await expect(page.getByRole("heading", { level: 1, name: `${idle.name} 的会话` })).toBeVisible();
    await expect(page.getByText("该用户当前没有在线会话")).toBeVisible();
    await expect(page.getByText("可能已退出、到期或被下线。")).toBeVisible();
    expect((await listedOnlineUsers(page)).has(idle.id)).toBe(false);
    await page.goto(`/sessions/${idle.id}`);
    await page.getByRole("link", { name: "返回在线用户" }).click();
    await expect(page).toHaveURL(/\/sessions$/);
  });
});

test.describe("SC-002：从任一页面到在线用户的会话详情不超过 2 次点击 / 触控", () => {
  test("从另一个用户的会话页：面包屑「会话管理」（1）→ 在线用户（2）", async ({ page, browser, baseURL }) => {
    await loginSeededUsers(browser, baseURL, page);
    await page.goto(`/sessions/${single.id}`);
    let clicks = 0;
    await page.getByRole("navigation", { name: "面包屑" }).getByRole("link", { name: "会话管理" }).click();
    clicks++;
    await expect(page.getByRole("heading", { level: 1, name: "会话管理" })).toBeVisible();

    await onlineItem(page, multi.name).getByRole("link", { name: multi.name }).click();
    clicks++;
    await expect(page).toHaveURL(new RegExp(`/sessions/${multi.id}$`));
    await expect(sessionCards(page)).toHaveCount(2);
    expect(clicks).toBeLessThanOrEqual(2);
  });

  test("桌面 / 平板的侧边栏：「会话管理」（1）→ 在线用户（2）", async ({ page, browser, baseURL }) => {
    test.skip(isNarrow(page), "手机上侧边栏是抽屉（先开抽屉再点导航），这条路径需要 3 次触控；手机走面包屑路径");
    await loginSeededUsers(browser, baseURL, page);
    await page.goto("/users");
    await page.getByRole("navigation", { name: "主导航" }).getByRole("link", { name: "会话管理" }).click();
    await expect(page).toHaveURL(/\/sessions$/);
    await onlineItem(page, single.name).getByRole("link", { name: single.name }).click();
    await expect(page).toHaveURL(new RegExp(`/sessions/${single.id}$`));
    await expect(sessionCards(page)).toHaveCount(1);
  });
});
