import { type APIRequestContext, type APIResponse } from "@playwright/test";
import { ADMIN, expect, login, seedSessionUsers, seedUsers, SESSIONS_SEED, test, USERS_SEED, userIdOfAccount } from "./support/fixtures";

/**
 * US13：RSC 局部渲染不能绕过登录（安全回归；FR-012、FR-033）。
 * 前提：栈已按 infra/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）。
 *
 * 漏洞原理：Next.js 15 的局部渲染只渲染「客户端带来的 Next-Router-State-Tree」里第一个不同的段起的部分，
 * 树里已含 `(shell)` 段时 `(shell)/layout.tsx` 根本不会被调用——而它曾是唯一拦截匿名访问者的地方；
 * 页面随后用服务密钥读 UAM，数据被序列化进 flight 响应。修复：每个受保护页面第一步自己调用 `requireOperator()`。
 *
 * 本用例不带任何 cookie（以及带一个失效 cookie）发 `RSC: 1` 请求，`Next-Router-State-Tree` 取多种值：
 * 与外壳布局匹配、与页面本身匹配、畸形值、缺省；对 /users、/users/<真实 guid>、/sessions、/sessions/<真实 guid>，
 * 断言响应里没有任何造数的用户名 / 账号名，且是重定向（到登录页或 /session/expire）或不含数据的载荷
 * （畸形状态树由 Next 自己在应用代码之前拒绝，允许 500，同样不得含数据）。
 * 对照：带有效会话 cookie 的同一请求（同一棵树）确实包含数据（证明标记可被检出），
 * 以及已登录用户在浏览器里的客户端导航（真正的 RSC 局部渲染）仍然正常。
 *
 * 造数用其它规格已有的固定造数（seedUsers、seedSessionUsers），本用例不新建用户，也不与其它规格的计数冲突。
 */

const COOKIE_NAME = process.env.E2E_SESSION_COOKIE_NAME ?? "asid"; // infra/local/e2e.env 的 SESSION_COOKIE_NAME
const adminName = ADMIN.name;

/** 任何一个出现在匿名响应里都算泄露：管理员、造数用户的用户名，以及造数账号名。 */
const MARKERS = [
  adminName,
  USERS_SEED.multi.name,
  USERS_SEED.none.name,
  USERS_SEED.disabled.name,
  ...USERS_SEED.multi.accounts,
  USERS_SEED.disabled.account,
  SESSIONS_SEED.multi.name,
  SESSIONS_SEED.single.name,
  ...SESSIONS_SEED.multi.accounts,
];

test.beforeAll(() => {
  seedUsers();
  seedSessionUsers();
});

/** 真实路由结构的状态树（与浏览器客户端发出的形态一致）：`["",{children:["(shell)",{children:[<目录段>,{children:[<动态段>,{children:["__PAGE__",{}]}]}]}]},null,null,true]`。 */
const wrap = (inner: unknown) => JSON.stringify(["", { children: ["(shell)", inner] }, null, null, true]);
const PAGE = { children: ["__PAGE__", {}] };
const treeOnlyShell = wrap(PAGE);

function variantsFor(path: string, userId?: string): Array<{ name: string; tree: string | undefined; malformed?: boolean }> {
  const [dir] = path.split("?")[0].split("/").filter(Boolean);
  const dirTree = wrap({ children: [dir, PAGE] });
  const pageTree = userId ? wrap({ children: [dir, { children: [["userId", userId, "d"], PAGE] }] }) : dirTree;
  return [
    { name: "树与页面本身匹配", tree: pageTree },
    { name: "树只到外壳布局（(shell) 段已存在）", tree: treeOnlyShell },
    { name: "树匹配外壳与目录段", tree: dirTree },
    { name: "畸形树（不是 JSON）", tree: "not-json", malformed: true },
    { name: "畸形树（空数组）", tree: "[]", malformed: true },
    { name: "没有状态树头（整页的 RSC 请求）", tree: undefined },
  ];
}

async function rsc(request: APIRequestContext, path: string, stateTree: string | undefined, cookie?: string): Promise<APIResponse> {
  const headers: Record<string, string> = { RSC: "1" };
  if (stateTree !== undefined) headers["Next-Router-State-Tree"] = encodeURIComponent(stateTree);
  if (cookie) headers.cookie = `${COOKIE_NAME}=${cookie}`;
  return request.get(path, { headers, maxRedirects: 0 });
}

/** 匿名响应：不含任何标记；状态只能是重定向（指向登录页 / 过期清理）或 200（载荷里无数据，如空载荷或客户端重定向指令）。 */
async function expectNoLeak(response: APIResponse, label: string, malformed = false) {
  const body = await response.text();
  for (const marker of MARKERS) expect(body, `${label}：响应里不能出现「${marker}」`).not.toContain(marker);
  const status = response.status();
  // 畸形状态树在应用代码运行之前就被 Next 自己拒绝（500）：没有数据即可，不要求重定向
  expect(malformed ? [200, 303, 307, 308, 400, 500] : [200, 303, 307, 308], `${label}：状态码 ${status}`).toContain(status);
  if ([303, 307, 308].includes(status)) expect(response.headers()["location"] ?? "", label).toMatch(/\/(login|session\/expire)/);
}

test.describe("匿名（无 cookie）发 RSC 局部渲染请求：不能读到任何数据", () => {
  let adminId: string;
  test.beforeAll(() => {
    adminId = userIdOfAccount(ADMIN.account);
  });

  test("先登录一次，让管理员在线（/sessions 才有数据可泄露）", async ({ page }) => {
    await login(page);
    await expect(page).toHaveURL(/\/users$/);
  });

  const targets = (id: () => string) => [
    { path: "/users" },
    { path: `/users?q=${ADMIN.account}` }, // 搜索词用账号名：用户名标记不能出现在请求里（Next 会把查询串回显进段键）
    { path: "/users/:id", dynamic: true, build: () => `/users/${id()}` },
    { path: "/sessions" },
    { path: "/sessions/:id", dynamic: true, build: () => `/sessions/${id()}` },
  ];

  for (const target of targets(() => adminId)) {
    test(`${target.path}：各种状态树都不泄露`, async ({ request }) => {
      const path = target.build ? target.build() : target.path;
      for (const v of variantsFor(path, target.dynamic ? adminId : undefined)) {
        await expectNoLeak(await rsc(request, path, v.tree), `${path} / ${v.name}`, v.malformed);
      }
    });

    test(`${target.path}：带失效 cookie 同样不泄露，且被送去清 cookie`, async ({ request }) => {
      const path = target.build ? target.build() : target.path;
      for (const v of variantsFor(path, target.dynamic ? adminId : undefined)) {
        await expectNoLeak(await rsc(request, path, v.tree, "stale-cookie-value"), `${path} / 失效 cookie / ${v.name}`, v.malformed);
      }
    });
  }

  test("对照：带有效会话的同一请求（同一棵树）确实带数据，说明标记可被检出", async ({ page, request }) => {
    await login(page);
    await expect(page).toHaveURL(/\/users$/);
    const cookie = (await page.context().cookies()).find((c) => c.name === COOKIE_NAME)?.value;
    expect(cookie).toBeTruthy();

    const path = `/users?q=${ADMIN.account}`;
    const authed = await rsc(request, path, treeOnlyShell, cookie);
    expect(authed.status()).toBe(200);
    expect(await authed.text()).toContain(adminName);

    const sessions = await rsc(request, "/sessions", treeOnlyShell, cookie);
    expect(sessions.status()).toBe(200);
    expect(await sessions.text()).toContain(adminName);

    const detail = await rsc(request, `/users/${adminId}`, treeOnlyShell, cookie);
    expect(detail.status()).toBe(200);
    expect(await detail.text()).toContain(adminName);
  });

  test("对照：已登录用户在浏览器里的客户端导航（真正的 RSC 局部渲染）仍然正常", async ({ page }) => {
    await login(page);
    await expect(page).toHaveURL(/\/users$/);
    // 页内链接与面包屑对所有宽度都可见：点击它们走 Next 的客户端导航（RSC 局部渲染，带状态树）
    const link = page.getByRole("main").getByRole("link", { name: "新建用户" });
    await link.click();
    await expect(page).toHaveURL(/\/users\/new$/);
    await expect(page.getByRole("heading", { level: 1, name: "新建用户" })).toBeVisible();
    await page.getByRole("navigation", { name: "面包屑" }).getByRole("link", { name: "用户管理" }).click();
    await expect(page).toHaveURL(/\/users$/);
    await expect(page.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();
    await expect(page.getByRole("main").getByRole("alert")).toHaveCount(0);
  });

  test("匿名浏览器访问受保护页面仍被送去登录页（普通导航不受影响）", async ({ browser, baseURL }) => {
    const context = await browser.newContext({ baseURL, extraHTTPHeaders: { "x-forwarded-for": "198.51.100.213" } });
    const page = await context.newPage();
    for (const path of ["/users", "/sessions", "/users/new", "/account/password"]) {
      await page.goto(path);
      await expect(page).toHaveURL(/\/login/);
    }
    await context.close();
  });
});
