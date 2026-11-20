import { expect, test, type Page } from "@playwright/test";
import { createUser, loginAsAdmin } from "./helpers";

const noPageScroll = (page: Page) =>
  page.evaluate(
    () => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1,
  );

test("the sidebar collapses and the choice survives a reload (FR-031, desktop)", async ({
  browser,
}) => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  await loginAsAdmin(page);
  await page.goto("/users");

  const aside = page.locator("aside");
  await expect(aside).toBeVisible();
  const expanded = (await aside.boundingBox())!.width;
  expect(expanded).toBeGreaterThan(200);

  await page.getByRole("button", { name: "收起侧边栏" }).click();
  await expect.poll(async () => (await aside.boundingBox())!.width).toBeLessThan(80);
  await expect(page.getByRole("link", { name: "用户管理" })).toBeVisible(); // 图标仍可点击

  await page.reload();
  await expect(page.getByRole("button", { name: "展开侧边栏" })).toBeVisible();
  await expect.poll(async () => (await aside.boundingBox())!.width).toBeLessThan(80);

  await page.getByRole("button", { name: "展开侧边栏" }).click();
  await expect.poll(async () => (await aside.boundingBox())!.width).toBeGreaterThan(200);
  expect(await noPageScroll(page)).toBe(true);
  await context.close();
});

test("on a 375px screen the sidebar is a drawer and no page scrolls sideways (FR-031)", async ({
  browser,
}) => {
  const admin = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const ap = await admin.newPage();
  await loginAsAdmin(ap);
  const user = await createUser(ap, { prefix: "e2e-narrow" });

  const context = await browser.newContext({ viewport: { width: 375, height: 812 } });
  const page = await context.newPage();
  await loginAsAdmin(page);
  await page.goto("/users");

  await expect(page.locator("aside")).toBeHidden();
  await page.getByRole("button", { name: "打开菜单" }).click();
  const drawer = page.getByRole("navigation", { name: "移动端导航" });
  await expect(drawer).toBeVisible();
  await drawer.getByRole("link", { name: "会话管理" }).click();
  await expect(page).toHaveURL(/\/sessions/);
  await expect(drawer).toHaveCount(0); // 选择菜单项后关闭

  await page.getByRole("button", { name: "打开菜单" }).click();
  await page.keyboard.press("Escape");
  await expect(drawer).toHaveCount(0);

  for (const path of ["/users", "/users/new", "/sessions", `/users/${user.id}`]) {
    await page.goto(path);
    await page.waitForLoadState("networkidle");
    expect(await noPageScroll(page), `${path} must not scroll sideways`).toBe(true);
  }

  await page.context().close();
  await admin.close();
});
