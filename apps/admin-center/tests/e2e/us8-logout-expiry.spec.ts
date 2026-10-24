import { type APIRequestContext, type Browser, type BrowserContext, type Locator, type Page } from "@playwright/test";
import { expect, liveSessions, login, test } from "./support/fixtures";

/**
 * US8：退出登录与会话结束后旧会话标识失效（quickstart S10；FR-009 ~ FR-011、FR-033；SC-013）。
 * 前提：栈已按 infra/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）；
 * UAM 已实现 US8（`POST /v1/auth/logout`，T148 ~ T149）。
 *
 * 覆盖会话结束的四种原因：主动退出（用户菜单）、被同一账号的新登录取代、被强制下线（US6）、所属用户被停用（US5）。
 * 每种之后都断言：用**旧的会话 cookie 值**发起的下一次请求被视为未登录（在全新的浏览器上下文里重放旧值，
 * 以及不经浏览器的裸 HTTP 请求），并且在线用户列表刷新后不再显示该会话（被取代时用户仍在线，但旧会话引用已不在）。
 * 同时用「仍有效的 cookie 值返回 200」作对照，证明断言能区分有效与失效。
 *
 * 空闲到期与绝对到期无法在共享栈上测：栈的时限是 30 分钟 / 12 小时（infra/local/e2e.env），也不允许为此改栈配置。
 * 它们由 UAM 的 `SessionExpiryIT`（T144，测试 profile 把时限调到秒级）覆盖：到期后 `verify` → `session_invalid`，
 * 到期会话不出现在在线用户里；Admin Center 对 `session_invalid` 的处理与这里的四种原因完全相同（`/session/expire`），
 * 所以这里不再重复。
 *
 * 造数：每个用例经「新建用户」界面（US4）建一个全新的用户及账号；名字带本次运行与视口的后缀，重复执行与六个视口项目互不冲突；
 * 名字刻意不含其它规格造数用户的子串（多账号用户、无账号用户、已停用用户、分页用户、在线者、离线者、建档员、停权对象、强退对象、
 * 改密者、甲号、乙号、丙号），避免串到它们的搜索与计数。被结束的会话只会是本用例刚造的用户的会话，绝不是 ADMIN 的。
 *
 * 「任一实例」：栈目前只有一个 Admin Center 实例，只对这一个实例断言；多实例在 US10 的 E2E 里补。
 */

const PASSWORD = "E2e-Logout-Pass-1";
const COOKIE_NAME = process.env.E2E_SESSION_COOKIE_NAME ?? "asid"; // infra/local/e2e.env 的 SESSION_COOKIE_NAME
const runId = Date.now().toString(36);

const widthOf = (page: Page) => page.viewportSize()?.width ?? 1280;
const isNarrow = (page: Page) => widthOf(page) < 768;

interface Target {
  id: string;
  userName: string;
  account: string;
}

/** 以 ADMIN 身份经界面建一个全新的用户及其账号；账号名 ≤ 32 个字符且只含 `[A-Za-z0-9_.-]`。 */
async function createTarget(page: Page, tag: string): Promise<Target> {
  const suffix = `${runId}${widthOf(page)}${tag}`;
  const userName = `终结验证${suffix}`;
  const account = `e2e.d8.${suffix}`;
  await page.goto("/users/new");
  await page.getByLabel("用户名", { exact: true }).fill(userName);
  await page.getByLabel("账号名", { exact: true }).fill(account);
  await page.getByLabel("初始密码", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: /^创建/ }).click();
  await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}\?created=1$/);
  const id = new URL(page.url()).pathname.split("/").pop()!;
  return { id, userName, account };
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

/** 在新上下文里以目标账号登录，返回该上下文与它当前持有的会话 cookie 值（旧值重放用）。 */
async function loginAs(browser: Browser, baseURL: string | undefined, like: Page, account: string) {
  const ctx = await newContext(browser, baseURL, like);
  await login(ctx.page, account, PASSWORD);
  await expect(ctx.page).toHaveURL(/\/users$/);
  const cookie = (await ctx.context.cookies()).find((c) => c.name === COOKIE_NAME);
  expect(cookie?.value).toBeTruthy();
  return { ...ctx, cookieValue: cookie!.value };
}

const sessionCookieOf = (context: BrowserContext) => context.cookies().then((all) => all.find((c) => c.name === COOKIE_NAME));

/** 目标用户在 Redis 里此刻有效的会话引用。 */
const refsOf = (userId: string) => liveSessions().filter((s) => s.userId === userId).map((s) => s.ref);

/** 不经浏览器、带着指定 cookie 值请求受保护页面，不跟随重定向。 */
const protectedStatus = (request: APIRequestContext, cookieValue: string, path = "/users") =>
  request.get(path, { headers: { cookie: `${COOKIE_NAME}=${cookieValue}` }, maxRedirects: 0 });

/** 断言「这个 cookie 值已不是登录状态」：裸请求被重定向走（不是 200），且在全新浏览器上下文里重放也回到登录页。 */
async function expectCookieIsLoggedOut(browser: Browser, request: APIRequestContext, baseURL: string | undefined, like: Page, value: string) {
  for (const path of ["/users", "/sessions"]) {
    const res = await protectedStatus(request, value, path);
    expect([303, 307, 308]).toContain(res.status());
    expect(res.headers()["location"]).toMatch(/\/(session\/expire|login)/);
  }

  const replay = await newContext(browser, baseURL, like);
  await replay.context.addCookies([{ name: COOKIE_NAME, value, url: baseURL ?? "http://localhost:3000", httpOnly: true, sameSite: "Strict" }]);
  await replay.page.goto("/users");
  await expect(replay.page).toHaveURL(/\/login/);
  await expect(replay.page.getByLabel("账号名")).toBeVisible();
  await replay.page.goto("/sessions");
  await expect(replay.page).toHaveURL(/\/login/);
}

/** 对照：仍有效的 cookie 值请求受保护页面返回 200。 */
async function expectCookieIsLive(request: APIRequestContext, value: string) {
  expect((await protectedStatus(request, value)).status()).toBe(200);
}

/** 在线用户列表里的每个用户：桌面是表格行，手机是卡片。 */
const onlineItems = (page: Page): Locator =>
  isNarrow(page)
    ? page.getByRole("list", { name: "在线用户卡片" }).getByRole("listitem")
    : page.getByRole("table", { name: "在线用户列表" }).locator("tbody tr");

/** 刷新并走完在线用户列表的所有页，收集用户标识（取自条目链接）。 */
async function listedOnlineUserIds(page: Page): Promise<Set<string>> {
  const listed = new Set<string>();
  await page.goto("/sessions");
  for (;;) {
    await expect(onlineItems(page).first()).toBeVisible();
    // 一次性取走本页所有链接：逐个 getAttribute 会和翻页导航竞态（末页行数更少）
    const hrefs = await onlineItems(page).getByRole("link").evaluateAll((links) => links.map((a) => a.getAttribute("href")));
    for (const href of hrefs) listed.add(href!.replace("/sessions/", ""));
    const next = page.getByRole("navigation", { name: "分页" }).getByRole("link", { name: "下一页" });
    if ((await next.count()) === 0) return listed;
    // 按链接跳转而不是点击：页面确实换成下一页后才继续
    await page.goto((await next.getAttribute("href"))!);
  }
}

/** 打开顶栏用户菜单并等进入动画结束（动画期间点击与测量都不稳定）。 */
async function openUserMenu(page: Page, userName: string): Promise<Locator> {
  await page.getByRole("button", { name: new RegExp(userName) }).click();
  const menu = page.getByRole("menu");
  await expect(menu).toBeVisible();
  await menu.evaluate((el) => Promise.all(el.getAnimations().map((animation) => animation.finished)));
  return menu;
}

async function logoutViaMenu(page: Page, userName: string) {
  const menu = await openUserMenu(page, userName);
  await menu.getByRole("menuitem", { name: "退出登录" }).click();
}

/** ADMIN 在目标用户的会话页强制下线其某个账号的会话（US6 的界面）。 */
async function forceTerminate(admin: Page, target: Target) {
  await admin.goto(`/sessions/${target.id}`);
  const card = admin.getByRole("list", { name: "会话列表" }).getByRole("listitem").filter({ has: admin.getByText(target.account, { exact: true }) });
  await card.getByRole("button", { name: "强制下线" }).click();
  const dialog = admin.getByRole("alertdialog", { name: "强制下线该会话？" });
  await dialog.getByRole("button", { name: "强制下线", exact: true }).click();
  await expect(admin.getByRole("status").filter({ hasText: "会话已强制下线。" })).toBeVisible();
}

test.beforeEach(async ({ page }) => {
  await login(page);
  await expect(page).toHaveURL(/\/users$/);
});

test.afterEach(async () => {
  await Promise.all(opened.splice(0).map((context) => context.close()));
});

test.describe("S10：主动退出", () => {
  test("用户菜单「退出登录」：cookie 被清、回登录页（无「会话已结束」提示）、UAM 会话终止、旧 cookie 值再请求被视为未登录、从在线列表消失；后退与受保护地址都回登录页", async ({
    page,
    browser,
    baseURL,
    request,
  }) => {
    const target = await createTarget(page, "a");
    const user = await loginAs(browser, baseURL, page, target.account);
    expect(refsOf(target.id)).toHaveLength(1);
    await expectCookieIsLive(request, user.cookieValue);
    expect(await listedOnlineUserIds(page)).toContain(target.id);

    await logoutViaMenu(user.page, target.userName);

    // 回登录页；这是主动退出，不是会话被结束，所以没有「会话已结束」提示
    await expect(user.page).toHaveURL(/\/login$/);
    await expect(user.page.getByLabel("账号名")).toBeVisible();
    await expect(user.page.getByText("会话已结束，请重新登录。")).toHaveCount(0);
    // 浏览器侧：cookie 已被清除
    expect(await sessionCookieOf(user.context)).toBeUndefined();

    // UAM 侧：会话立即终止；旧值再请求被视为未登录；在线列表刷新后不再显示该用户
    expect(refsOf(target.id)).toHaveLength(0);
    await expectCookieIsLoggedOut(browser, request, baseURL, page, user.cookieValue);
    expect(await listedOnlineUserIds(page)).not.toContain(target.id);
    await page.goto(`/sessions/${target.id}`);
    await expect(page.getByText("该用户当前没有在线会话")).toBeVisible();

    // 后退不会回到受保护页面；直接访问受保护地址回登录页（带原路径，登录后可回到那里）
    await user.page.goBack();
    await expect(user.page).toHaveURL(/\/login/);
    await user.page.goto("/sessions");
    await expect(user.page).toHaveURL(/\/login\?next=%2Fsessions$/);

    // 退出后可以再次登录
    await login(user.page, target.account, PASSWORD);
    await expect(user.page).toHaveURL(/\/users$/);
    expect(refsOf(target.id)).toHaveLength(1);
  });

  test("会话已在别处结束后再点「退出登录」：同样回登录页并清 cookie，不报错（退出幂等）", async ({ page, browser, baseURL, request }) => {
    const target = await createTarget(page, "b");
    const user = await loginAs(browser, baseURL, page, target.account);
    // 用户的页面还停着（菜单在页面里），此时 ADMIN 把这个会话强制下线
    await forceTerminate(page, target);
    expect(refsOf(target.id)).toHaveLength(0);

    await logoutViaMenu(user.page, target.userName);
    await expect(user.page).toHaveURL(/\/login/);
    await expect(user.page.getByLabel("账号名")).toBeVisible();
    expect(await sessionCookieOf(user.context)).toBeUndefined();
    await expectCookieIsLoggedOut(browser, request, baseURL, page, user.cookieValue);
  });
});

test.describe("SC-013：会话因其它原因结束后，旧会话标识 100% 被视为未登录", () => {
  test("被同一账号的新登录取代：旧 cookie 值失效、新会话有效；在线列表里旧会话引用不再出现，用户仍在线（只剩新会话）", async ({
    page,
    browser,
    baseURL,
    request,
  }) => {
    const target = await createTarget(page, "c");
    const first = await loginAs(browser, baseURL, page, target.account);
    const [oldRef] = refsOf(target.id);
    expect(oldRef).toBeTruthy();
    await expectCookieIsLive(request, first.cookieValue);

    const second = await loginAs(browser, baseURL, page, target.account);
    expect(second.cookieValue).not.toBe(first.cookieValue);

    // 旧 cookie 值被视为未登录；新值仍有效
    await expectCookieIsLoggedOut(browser, request, baseURL, page, first.cookieValue);
    await expectCookieIsLive(request, second.cookieValue);
    // 旧会话所在的页面下一次请求也回登录页并提示会话已结束
    await first.page.goto("/users");
    await expect(first.page).toHaveURL(/\/login\?.*reason=session_ended/);
    await expect(first.page.getByText("会话已结束，请重新登录。")).toBeVisible();

    // UAM / 在线列表：只剩新会话；旧会话引用不在会话页上
    const refs = refsOf(target.id);
    expect(refs).toHaveLength(1);
    expect(refs).not.toContain(oldRef);
    expect(await listedOnlineUserIds(page)).toContain(target.id);
    await page.goto(`/sessions/${target.id}`);
    await expect(page.getByRole("list", { name: "会话列表" }).getByRole("listitem")).toHaveCount(1);
    await expect(page.getByText(oldRef.slice(0, 8), { exact: true })).toHaveCount(0);
  });

  test("被强制下线（US6）：旧 cookie 值失效，在线列表刷新后不再显示该用户", async ({ page, browser, baseURL, request }) => {
    const target = await createTarget(page, "d");
    const user = await loginAs(browser, baseURL, page, target.account);
    await expectCookieIsLive(request, user.cookieValue);
    expect(await listedOnlineUserIds(page)).toContain(target.id);

    await forceTerminate(page, target);

    expect(refsOf(target.id)).toHaveLength(0);
    await expectCookieIsLoggedOut(browser, request, baseURL, page, user.cookieValue);
    expect(await listedOnlineUserIds(page)).not.toContain(target.id);
  });

  test("所属用户被停用（US5）：旧 cookie 值失效，在线列表刷新后不再显示该用户", async ({ page, browser, baseURL, request }) => {
    const target = await createTarget(page, "e");
    const user = await loginAs(browser, baseURL, page, target.account);
    await expectCookieIsLive(request, user.cookieValue);
    expect(await listedOnlineUserIds(page)).toContain(target.id);

    await page.goto(`/users/${target.id}`);
    await page.getByRole("button", { name: "停用用户" }).click();
    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "停用", exact: true }).click();
    await expect(page.getByRole("status").filter({ hasText: `用户「${target.userName}」已停用，其会话已结束。` })).toBeVisible();

    expect(refsOf(target.id)).toHaveLength(0);
    await expectCookieIsLoggedOut(browser, request, baseURL, page, user.cookieValue);
    expect(await listedOnlineUserIds(page)).not.toContain(target.id);
  });
});

test.describe("POST /logout 的入口约束（FR-033）", () => {
  test("跨站来源的 POST 被拒（403）且不退出；GET /logout 不会退出（405）；会话仍有效", async ({ page, browser, baseURL, request }) => {
    const target = await createTarget(page, "f");
    const user = await loginAs(browser, baseURL, page, target.account);
    const cookie = `${COOKIE_NAME}=${user.cookieValue}`;

    const cross = await request.post("/logout", {
      headers: { cookie, origin: "http://evil.example", "sec-fetch-site": "cross-site" },
      maxRedirects: 0,
    });
    expect(cross.status()).toBe(403);
    expect(cross.headers()["set-cookie"]).toBeUndefined();

    const noOrigin = await request.post("/logout", { headers: { cookie }, maxRedirects: 0 });
    expect(noOrigin.status()).toBe(403);

    const get = await request.get("/logout", { headers: { cookie }, maxRedirects: 0 });
    expect(get.status()).toBe(405);

    // 以上都没有终止会话：UAM 里仍有会话，cookie 值仍有效
    expect(refsOf(target.id)).toHaveLength(1);
    await expectCookieIsLive(request, user.cookieValue);
    await user.page.goto("/users");
    await expect(user.page).toHaveURL(/\/users$/);
  });
});
