import { expect, test } from "@playwright/test";
import {
  NEW_PASSWORD,
  changePassword,
  createUser,
  formAlert,
  login,
  loginAsAdmin,
  newPage,
} from "./helpers";

const JWT = /eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/;

test.describe("login journey (quickstart 1, 2, 13)", () => {
  test("login page has no tenant input and none of the removed entries", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByLabel("用户名")).toBeVisible();
    await expect(page.getByLabel(/租户/)).toHaveCount(0);
    for (const text of ["忘记密码", "企业 SSO", "扫码登录", "7 天内免登录"]) {
      await expect(page.getByText(text)).toHaveCount(0);
    }
  });

  test("wrong credentials show the unified message", async ({ page }) => {
    await login(page, "no-such-user-e2e", "Wrong-Passw0rd!1");
    await expect(formAlert(page)).toHaveText("用户名或密码错误");
    await expect(page).toHaveURL(/\/login/);
  });

  test("login → forced password change → user list, cookie is HttpOnly and no JWT reaches the browser", async ({
    browser,
  }) => {
    const admin = await newPage(browser);
    await loginAsAdmin(admin.page);
    const user = await createUser(admin.page, { role: "TENANT_ADMIN", prefix: "e2e-adm" });

    const { context, page } = await newPage(browser);
    const bodies: string[] = [];
    page.on("response", async (res) => {
      if (res.url().includes("/api/v1/")) bodies.push(await res.text().catch(() => ""));
    });

    const started = Date.now();
    await login(page, user.username, user.temporaryPassword);
    await expect(page).toHaveURL(/\/change-password/);
    // 改密前访问其他页面一律回到改密页
    await page.goto("/users");
    await expect(page).toHaveURL(/\/change-password/);
    await changePassword(page, user.temporaryPassword, NEW_PASSWORD);
    await expect(page).toHaveURL(/\/users/);
    await expect(page.getByRole("heading", { name: "用户管理" })).toBeVisible();
    // SC-001：从打开登录页到进入用户列表 ≤ 10 秒
    expect(Date.now() - started).toBeLessThan(10_000);

    // 侧边栏只有两项
    const nav = page.getByRole("navigation", { name: "主导航" });
    await expect(nav.getByRole("link")).toHaveText(["用户管理", "会话管理"]);

    const cookie = (await context.cookies()).find((c) => c.name === "SESSION_ID");
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite).toBe("Lax");
    expect(await page.evaluate(() => document.cookie)).not.toContain("SESSION_ID");

    // 浏览器端拿不到 JWT：响应体、存储、页面都不含 JWT
    expect(bodies.join("\n")).not.toMatch(JWT);
    const storage = await page.evaluate(() =>
      JSON.stringify([{ ...localStorage }, { ...sessionStorage }]),
    );
    expect(storage).not.toMatch(JWT);
    expect(await page.content()).not.toMatch(JWT);

    // 退出登录后受保护页回到登录页
    await page.getByRole("button", { name: /E2E/ }).click();
    await page.getByRole("menuitem", { name: "退出登录" }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.goto("/users");
    await expect(page).toHaveURL(/\/login\?next=%2Fusers/);

    await context.close();
    await admin.context.close();
  });

  test("a MEMBER is sent to /forbidden and has no admin navigation", async ({ browser }) => {
    const admin = await newPage(browser);
    await loginAsAdmin(admin.page);
    const member = await createUser(admin.page, { role: "MEMBER", prefix: "e2e-mem" });

    const { context, page } = await newPage(browser);
    await login(page, member.username, member.temporaryPassword);
    await changePassword(page, member.temporaryPassword, NEW_PASSWORD);
    await expect(page).toHaveURL(/\/forbidden/);
    await expect(page.getByRole("heading", { name: "无权访问" })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "主导航" })).toHaveCount(0);

    await page.goto("/users");
    await expect(page).toHaveURL(/\/forbidden/);
    // 管理接口 403 FORBIDDEN
    const status = await page.evaluate(async () => (await fetch("/api/v1/users")).status);
    expect(status).toBe(403);

    await context.close();
    await admin.context.close();
  });

  test("a cross-site write to the BFF is rejected", async ({ browser }) => {
    const admin = await newPage(browser);
    await loginAsAdmin(admin.page);
    const res = await admin.context.request.post("/api/v1/auth/logout", {
      headers: { origin: "http://evil.example", "sec-fetch-site": "cross-site" },
    });
    expect(res.status()).toBe(403);
    await admin.page.goto("/users");
    await expect(admin.page).toHaveURL(/\/users/); // 会话未受影响
    await admin.context.close();
  });
});
