import { test as base, expect, type Page } from "@playwright/test";
import { uamCli } from "./stack";

/** 本地 E2E 的测试账号；密码只在测试里使用。 */
export const ADMIN = { account: "admin", password: "E2e-Admin-Pass-1", name: "系统管理员" };

/** 部署期命令 init-admin：造出初始管理员（栈刚起来时调用一次）。 */
export function seedAdmin(): void {
  uamCli(["init-admin", "--account-name", ADMIN.account, "--user-name", ADMIN.name], `${ADMIN.password}\n`);
}

/** 登录辅助：走真实的登录页。 */
export async function login(page: Page, account = ADMIN.account, password = ADMIN.password): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("账号").fill(account);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: "登录" }).click();
}

/** 栈前面没有反向代理：用例统一带上 X-Forwarded-For 作为「终端来源地址」（playwright.config.ts 的 use.extraHTTPHeaders 引用）。 */
export const SOURCE_HEADERS = { "x-forwarded-for": "203.0.113.7" };

export const test = base;

export { expect };
