import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { expect, test, type Page } from "@playwright/test";

/**
 * k8s 部署的冒烟测试：登录 → 用户列表 → 会话列表 → 退出 → 受保护页面回登录页。
 * 账号是 `deploy.sh test-account` 建的专用测试账号；密码只在这里从本机文件读出，不经命令行、不打印。
 * 入口后面有 2 个 Admin Center 与 2 个 UAM 副本，连续几次请求会落到不同副本上：会话在 UAM 的 Redis 里，任一副本都必须认。
 */
const ACCOUNT = process.env.K8S_TEST_ACCOUNT ?? "k8s-test";
const PASSWORD_FILE =
  process.env.TEST_PASSWORD_FILE ?? path.join(process.env.XDG_CONFIG_HOME ?? path.join(os.homedir(), ".config"), "tech-toolkit", "k8s-test-account-password");

function readPassword(): string {
  try {
    return fs.readFileSync(PASSWORD_FILE, "utf8").trim();
  } catch {
    throw new Error(`读不到测试账号的密码文件 ${PASSWORD_FILE}：先执行 devops/k8s/scripts/deploy.sh test-account`);
  }
}

async function login(page: Page, account: string, password: string): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("账号").fill(account);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: "登录" }).click();
}

test.describe("k8s 部署冒烟", () => {
  test("错误密码：提示账号名或密码错误，不泄露账号是否存在", async ({ page }) => {
    await login(page, ACCOUNT, "definitely-wrong-password");
    await expect(page.getByRole("alert").filter({ hasText: "账号名或密码错误" })).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test("正确密码登录 → 用户列表 → 会话列表 → 退出 → 受保护页面回登录页", async ({ page }) => {
    await login(page, ACCOUNT, readPassword());

    // 登录成功落在用户列表，顶栏有用户名
    await expect(page).toHaveURL(/\/users/);
    await expect(page.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();
    const userMenu = page.getByRole("button", { name: /系统管理员/ });
    await expect(userMenu).toBeVisible();
    await expect(page.getByRole("table", { name: "用户列表" }).locator("tbody tr").first()).toBeVisible();

    // 会话管理：在线用户里至少有自己（会话与在线集合都在 UAM 的 Redis 里）
    await page.goto("/sessions");
    await expect(page.getByRole("heading", { level: 1, name: "会话管理" })).toBeVisible();
    await expect(page.getByRole("table", { name: "在线用户列表" }).locator("tbody tr").first()).toBeVisible();

    // 多刷新几次：请求会被分到不同的 Admin Center / UAM 副本，会话必须在每个副本上都有效
    for (let i = 0; i < 6; i++) {
      await page.goto("/users");
      await expect(page.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();
    }

    // 退出：回登录页；之后访问受保护页面仍回登录页
    await userMenu.click();
    await page.getByRole("menuitem", { name: "退出登录" }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.goto("/users");
    await expect(page).toHaveURL(/\/login/);
  });
});
