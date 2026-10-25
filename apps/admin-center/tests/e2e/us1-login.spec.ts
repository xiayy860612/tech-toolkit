import AxeBuilder from "@axe-core/playwright";
import { type Browser, type BrowserContext, type Page } from "@playwright/test";
import { uamCli } from "./support/stack";
import { ADMIN, expect, login, SOURCE_HEADERS, test } from "./support/fixtures";

/**
 * US1：账号名 / 密码登录（quickstart S1 ~ S3；FR-001 ~ FR-007、FR-012；SC-001）。
 * 前提：栈已按 infra/local/scripts/bootstrap.sh 起好，且已 init-admin 出 ADMIN（见 support/fixtures.ts）。
 * 登录成功后落在 /users，所以「看到用户列表」「顶栏用户名」依赖 US2 的 /users 页面与外壳。
 */

/** 同一用户的第二个账号（只能由部署期命令 add-account 产生）。 */
const SECOND = { account: "admin.second", password: "E2e-Second-Pass-1" };
const COOKIE_NAME = process.env.E2E_SESSION_COOKIE_NAME ?? "asid"; // infra/local/e2e.env 的 SESSION_COOKIE_NAME
const SC001_BUDGET_MS = 30_000;

const userMenu = (page: Page) => page.getByRole("button", { name: new RegExp(ADMIN.name) });
// Next 自带的路由播报器也是 role=alert，按文案过滤避免多重匹配
const failure = (page: Page, text: string | RegExp) => page.getByRole("alert").filter({ hasText: text });

/** 每个浏览器上下文用独立的来源地址，避免登录失败限制串到其他用例。 */
async function contextFrom(browser: Browser, baseURL: string | undefined, viewport: Page, ip?: string) {
  return browser.newContext({
    baseURL,
    viewport: viewport.viewportSize() ?? undefined,
    extraHTTPHeaders: ip ? { "x-forwarded-for": ip } : SOURCE_HEADERS,
  });
}

const randomIp = () => `198.51.100.${1 + Math.floor(Math.random() * 250)}`;

async function sessionCookie(context: BrowserContext) {
  return (await context.cookies()).find((c) => c.name === COOKIE_NAME);
}

test.beforeAll(() => {
  try {
    uamCli(["add-account", "--account-name-of", ADMIN.account, "--account-name", SECOND.account], `${SECOND.password}\n`);
  } catch {
    // 已存在（其它视口项目先跑过）：账号名唯一，重复添加被拒绝，视为已就绪
  }
});

test.describe("登录页", () => {
  test("字段、按钮与可访问性；320 ~ 2560 宽度内无整页横向滚动", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("heading", { level: 1, name: "Admin Center" })).toBeVisible();
    await expect(page.getByLabel("账号名")).toHaveAttribute("autocomplete", "username");
    await expect(page.getByLabel("密码")).toHaveAttribute("autocomplete", "current-password");
    await expect(page.getByLabel("密码")).toHaveAttribute("type", "password");
    await expect(page.getByRole("button", { name: "登录" })).toBeVisible();

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);

    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations.filter((v) => v.impact === "serious" || v.impact === "critical")).toEqual([]);
  });

  test("账号名或密码为空：字段旁提示，不会登录", async ({ page }) => {
    await page.goto("/login");
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page.getByText("请填写账号名")).toBeVisible();
    await expect(page.getByText("请填写密码")).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
    expect(await sessionCookie(page.context())).toBeUndefined();
  });
});

test.describe("S1：多账号登录识别为同一用户", () => {
  test("账号 A、账号 B 分别登录，都进入 Admin Center，顶栏是同一用户名；cookie 为 HttpOnly + Secure + SameSite=Strict", async ({
    browser,
    baseURL,
    page,
  }) => {
    for (const [account, password] of [
      [ADMIN.account, ADMIN.password],
      [SECOND.account, SECOND.password],
    ]) {
      const context = await contextFrom(browser, baseURL, page);
      const p = await context.newPage();
      await login(p, account, password);
      await expect(p).toHaveURL(/\/users$/);
      await expect(userMenu(p)).toBeVisible();

      const cookie = await sessionCookie(context);
      expect(cookie, `账号 ${account} 登录后应有会话 cookie`).toBeDefined();
      expect(cookie!.value.length).toBeGreaterThan(0);
      expect(cookie!.httpOnly).toBe(true);
      expect(cookie!.secure).toBe(true);
      expect(cookie!.sameSite).toBe("Strict");
      expect(cookie!.path).toBe("/");
      // HttpOnly：页面脚本读不到会话标识
      expect(await p.evaluate(() => document.cookie)).not.toContain(cookie!.value);
      await context.close();
    }
  });
});

test.describe("S2：失败提示统一与限流", () => {
  test("错密码与不存在的账号名：提示完全一致；不回填密码；没有会话 cookie", async ({ page }) => {
    await login(page, ADMIN.account, "definitely-wrong-1");
    await expect(failure(page, "账号名或密码错误")).toBeVisible();
    const wrongPasswordText = await failure(page, "账号名或密码错误").innerText();
    await expect(page.getByLabel("密码")).toHaveValue("");
    await expect(page.getByLabel("账号名")).toHaveValue(ADMIN.account);
    expect(await sessionCookie(page.context())).toBeUndefined();

    // 不存在的账号名也按「账号名 + 来源地址」计失败次数，且永远登录不成功、计数不清零：
    // 每次运行用独立的账号名，免得跨视口项目累计到限流阈值（第 6 个项目会被拦）
    const unknownAccount = `no.such.${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;
    await login(page, unknownAccount, "definitely-wrong-1");
    await expect(failure(page, "账号名或密码错误")).toBeVisible();
    expect(await failure(page, "账号名或密码错误").innerText()).toBe(wrongPasswordText);
  });

  test("同一来源连错 5 次后，第 6 次（即使密码正确）被拒并提示稍后再试；其他来源仍可登录", async ({
    browser,
    baseURL,
    page,
  }) => {
    const blocked = await contextFrom(browser, baseURL, page, randomIp());
    const p = await blocked.newPage();
    for (let i = 0; i < 5; i++) {
      await login(p, ADMIN.account, `wrong-pass-${i}`);
      await expect(failure(p, "账号名或密码错误")).toBeVisible();
    }
    await login(p, ADMIN.account, ADMIN.password);
    await expect(failure(p, /尝试过于频繁，请 \d+ 秒后再试/)).toBeVisible();
    expect(await sessionCookie(blocked)).toBeUndefined();
    await expect(p).toHaveURL(/\/login/);

    // 另一个来源不受影响
    await login(page, ADMIN.account, ADMIN.password);
    await expect(page).toHaveURL(/\/users$/);
    await blocked.close();
  });
});

test.describe("S3：同一账号只有一个会话", () => {
  test("第二个浏览器登录后，第一个浏览器的下一次请求被带到登录页并提示会话已结束", async ({ browser, baseURL, page }) => {
    const first = await contextFrom(browser, baseURL, page);
    const second = await contextFrom(browser, baseURL, page);
    const p1 = await first.newPage();
    const p2 = await second.newPage();

    await login(p1, ADMIN.account, ADMIN.password);
    await expect(p1).toHaveURL(/\/users$/);
    await login(p2, ADMIN.account, ADMIN.password);
    await expect(p2).toHaveURL(/\/users$/);

    await p1.goto("/users");
    await expect(p1).toHaveURL(/\/login/);
    await expect(p1.getByRole("status").filter({ hasText: "会话已结束，请重新登录。" })).toBeVisible();
    // 过期 cookie 已被清除
    expect(await sessionCookie(first)).toBeUndefined();
    // 第二个浏览器的会话不受影响
    await p2.goto("/users");
    await expect(p2).toHaveURL(/\/users$/);

    await first.close();
    await second.close();
  });
});

test.describe("受保护路由与 next（FR-012）", () => {
  const deepLink = "/users/00000000-0000-4000-8000-000000000000";

  test("未登录访问深链接：被带到 /login?next=原路径，登录后回到原页", async ({ page }) => {
    await page.goto(deepLink);
    await expect(page).toHaveURL(new RegExp(`/login\\?next=${encodeURIComponent(deepLink)}`));
    await page.getByLabel("账号名").fill(ADMIN.account);
    await page.getByLabel("密码").fill(ADMIN.password);
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page).toHaveURL(new RegExp(`${deepLink}$`));
    await expect(userMenu(page)).toBeVisible();
  });

  test("未登录访问 /：登录后进入 /users", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/login/);
    await page.getByLabel("账号名").fill(ADMIN.account);
    await page.getByLabel("密码").fill(ADMIN.password);
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page).toHaveURL(/\/users$/);
  });

  for (const next of ["//evil.example/phish", "https://evil.example/phish", "/\\evil.example"]) {
    test(`站外 next=${next} 被忽略：登录后留在本站的 /users`, async ({ page, baseURL }) => {
      await page.goto(`/login?next=${encodeURIComponent(next)}`);
      await page.getByLabel("账号名").fill(ADMIN.account);
      await page.getByLabel("密码").fill(ADMIN.password);
      await page.getByRole("button", { name: "登录" }).click();
      await expect(page).toHaveURL(/\/users$/);
      expect(new URL(page.url()).origin).toBe(new URL(baseURL ?? page.url()).origin);
    });
  }

  test("已登录访问 /login：重定向到 /users", async ({ page }) => {
    await login(page);
    await expect(page).toHaveURL(/\/users$/);
    await page.goto("/login");
    await expect(page).toHaveURL(/\/users$/);
  });
});

test.describe("SC-001：从打开登录页到看到用户列表", () => {
  test("含输入在内不超过 30 秒（记录实际耗时；95% 分位需多轮汇总）", async ({ page }, testInfo) => {
    const started = Date.now();
    await page.goto("/login");
    await page.getByLabel("账号名").fill(ADMIN.account);
    await page.getByLabel("密码").fill(ADMIN.password);
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();
    const elapsed = Date.now() - started;

    testInfo.annotations.push({ type: "SC-001", description: `${elapsed} ms（${testInfo.project.name}）` });
    console.log(`[SC-001] ${testInfo.project.name}: ${elapsed} ms`);
    expect(elapsed).toBeLessThanOrEqual(SC001_BUDGET_MS);
  });
});
