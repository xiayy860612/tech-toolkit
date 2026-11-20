import { expect, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { randomBytes } from "node:crypto";

/** 开发种子管理员（backend application-dev.yml 明示的测试凭证）；首次登录会被强制改密。 */
export const SEED_ADMIN = {
  username: process.env.E2E_ADMIN_USERNAME ?? "admin",
  password: process.env.E2E_ADMIN_SEED_PASSWORD ?? "Dev-Only-Passw0rd!",
};
/** 种子管理员改密后的密码（E2E 专用，仅用于本地开发库）。 */
export const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? "E2e-Root-Passw0rd!x";
export const NEW_PASSWORD = "E2e-New-Passw0rd!x";

export const uniqueName = (prefix: string) => `${prefix}-${randomBytes(3).toString("hex")}`;

/** 表单内的错误提示（排除 Next 的路由播报区，它同样是 role=alert）。 */
export const formAlert = (page: Page) => page.locator("form").getByRole("alert");

const LANDED = /\/(users|change-password|forbidden)(\/|$|\?)/;

/** 在登录页提交凭证，等到跳转或出现错误提示。 */
export async function login(page: Page, username: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("用户名").fill(username);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: "登录" }).click();
  await Promise.race([page.waitForURL(LANDED), formAlert(page).waitFor()]);
}

export async function changePassword(page: Page, current: string, next: string) {
  await expect(page).toHaveURL(/\/change-password/);
  await page.getByLabel("当前密码").fill(current);
  await page.getByLabel("新密码", { exact: true }).fill(next);
  await page.getByLabel("确认新密码").fill(next);
  await page.getByRole("button", { name: "修改密码" }).click();
}

/** 以管理员身份登录；种子管理员若仍是初始密码则先完成强制改密（对重复运行幂等）。 */
export async function loginAsAdmin(page: Page) {
  await login(page, SEED_ADMIN.username, ADMIN_PASSWORD);
  if (page.url().includes("/users")) return;
  await login(page, SEED_ADMIN.username, SEED_ADMIN.password);
  await changePassword(page, SEED_ADMIN.password, ADMIN_PASSWORD);
  await expect(page).toHaveURL(/\/users/);
}

export async function newPage(browser: Browser): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext();
  return { context, page: await context.newPage() };
}

/** 在页面内以同源 fetch 调用 BFF（浏览器自带 Origin / Sec-Fetch-Site，通过来源校验）。 */
export async function apiFetch<T = unknown>(
  page: Page,
  method: string,
  path: string,
  body?: unknown,
): Promise<{ status: number; body: T }> {
  return page.evaluate(
    async ({ method, path, body }) => {
      const res = await fetch(`/api/v1${path}`, {
        method,
        headers: body === undefined ? {} : { "content-type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await res.text();
      return { status: res.status, body: text ? JSON.parse(text) : undefined };
    },
    { method, path, body },
  );
}

export interface CreatedUser {
  id: string;
  username: string;
  displayName: string;
  temporaryPassword: string;
}

/** 管理员（已登录的 page）创建用户，返回一次性临时密码。 */
export async function createUser(
  adminPage: Page,
  opts: { role?: "TENANT_ADMIN" | "MEMBER"; prefix?: string } = {},
): Promise<CreatedUser> {
  const username = uniqueName(opts.prefix ?? "e2e");
  const displayName = `E2E ${username}`;
  const res = await apiFetch<{
    user: { id: string };
    account: { temporaryPassword: string };
  }>(adminPage, "POST", "/users", {
    displayName,
    phone: null,
    email: null,
    role: opts.role ?? "MEMBER",
    account: { type: "USERNAME_PASSWORD", username },
  });
  expect(res.status).toBe(201);
  return {
    id: res.body.user.id,
    username,
    displayName,
    temporaryPassword: res.body.account.temporaryPassword,
  };
}

/** 新用户首次登录：临时密码 → 强制改密 → 返回新密码。 */
export async function firstLogin(page: Page, user: CreatedUser): Promise<string> {
  await login(page, user.username, user.temporaryPassword);
  await changePassword(page, user.temporaryPassword, NEW_PASSWORD);
  await page.waitForURL(/\/(users|forbidden)/);
  return NEW_PASSWORD;
}
