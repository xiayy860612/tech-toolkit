import { expect, test } from "@playwright/test";
import { NEW_PASSWORD, changePassword, login, loginAsAdmin, newPage, uniqueName } from "./helpers";

test.describe("users journey (quickstart 5, 6)", () => {
  test("create a user (system-generated password) → the user logs in and is forced to change it", async ({
    browser,
  }) => {
    const admin = await newPage(browser);
    const page = admin.page;
    await loginAsAdmin(page);

    const username = uniqueName("e2e-new");
    await page.goto("/users/new");
    await page.getByLabel(/姓名/).fill(`E2E ${username}`);
    await page.getByLabel(/登录用户名/).fill(username);
    await page.getByRole("button", { name: "创建用户" }).click();

    const dialog = page.getByRole("dialog", { name: /临时密码/ });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText(/关闭后不可再次查看/)).toBeVisible();
    const temporaryPassword = (await dialog.locator("code").innerText()).trim();
    expect(temporaryPassword.length).toBeGreaterThanOrEqual(16);
    await dialog.getByRole("button", { name: "我已记下，关闭" }).click();

    // 关闭后进入详情页，且临时密码不再可见
    await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}$/);
    await expect(page.getByRole("heading", { name: `E2E ${username}` })).toBeVisible();
    await expect(page.getByText(temporaryPassword)).toHaveCount(0);

    // 列表可搜到
    await page.goto("/users");
    await page.getByLabel("关键词").fill(username);
    await page.getByRole("button", { name: "搜索" }).click();
    await expect(page.getByRole("row", { name: new RegExp(username) })).toBeVisible();

    // 新用户登录并被强制改密
    const user = await newPage(browser);
    await login(user.page, username, temporaryPassword);
    await expect(user.page).toHaveURL(/\/change-password/);
    await changePassword(user.page, temporaryPassword, NEW_PASSWORD);
    await expect(user.page).toHaveURL(/\/forbidden/);

    await user.context.close();
    await admin.context.close();
  });

  test("a duplicate username is rejected inline", async ({ browser }) => {
    const admin = await newPage(browser);
    const page = admin.page;
    await loginAsAdmin(page);

    const username = uniqueName("e2e-dup");
    for (const attempt of [1, 2]) {
      await page.goto("/users/new");
      await page.getByLabel(/姓名/).fill(`E2E dup ${attempt}`);
      await page.getByLabel(/登录用户名/).fill(username);
      await page.getByRole("button", { name: "创建用户" }).click();
      if (attempt === 1) {
        await page.getByRole("button", { name: "我已记下，关闭" }).click();
        await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}$/);
      }
    }
    await expect(page.getByText("该用户名已被使用")).toBeVisible();
    await expect(page.getByLabel(/登录用户名/)).toHaveAttribute("aria-invalid", "true");
    await admin.context.close();
  });

  test("validation summary appears in the sticky bar", async ({ browser }) => {
    const admin = await newPage(browser);
    await loginAsAdmin(admin.page);
    await admin.page.goto("/users/new");
    await admin.page.getByRole("button", { name: "创建用户" }).click();
    await expect(admin.page.getByText("有 2 项校验未通过")).toBeVisible();
    await admin.context.close();
  });

  // 「同名用户名在另一租户可创建」（quickstart 场景 6 的后半）本期无法经 UI 验证：
  // 租户固定为 0，没有租户管理与第二个租户的入口。该约束由后端 SameUsernameAcrossTenantsIT 覆盖。
});
