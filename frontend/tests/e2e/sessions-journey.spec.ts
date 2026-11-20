import { expect, test, type Page } from "@playwright/test";
import { createUser, firstLogin, login, loginAsAdmin, newPage } from "./helpers";

/** 以 MEMBER 身份访问一个受保护页：仍在 /forbidden 说明会话有效，回到 /login 说明会话已失效。 */
async function sessionState(page: Page): Promise<"valid" | "revoked"> {
  await page.goto("/forbidden");
  await page.waitForURL(/\/(forbidden|login)/);
  return page.url().includes("/login") ? "revoked" : "valid";
}

test("force a session offline, then all sessions of a user (quickstart 19)", async ({
  browser,
}) => {
  const admin = await newPage(browser);
  const ap = admin.page;
  await loginAsAdmin(ap);

  // 同一用户在两个浏览器上下文各登录一次
  const user = await createUser(ap, { role: "MEMBER", prefix: "e2e-ses" });
  const first = await newPage(browser);
  const password = await firstLogin(first.page, user); // 改密后保留当前会话
  const second = await newPage(browser);
  await login(second.page, user.username, password);
  await expect(second.page).toHaveURL(/\/forbidden/);

  // 管理员的「会话管理」页：按用户筛选，看到该用户的两个会话
  await ap.goto("/sessions");
  await expect(ap.getByRole("heading", { name: "会话管理" })).toBeVisible();
  await ap.getByLabel("用户关键字").fill(user.username);
  await ap.getByRole("button", { name: "搜索" }).click();
  const userRows = ap.getByRole("row").filter({ hasText: user.username });
  await expect(userRows).toHaveCount(2);

  // 管理员自己的当前会话标「当前」且没有操作
  await ap.getByLabel("用户关键字").fill("");
  await ap.getByRole("button", { name: "搜索" }).click();
  const currentRow = ap.getByRole("row").filter({ has: ap.getByText("当前", { exact: true }) });
  await expect(currentRow).toHaveCount(1);
  await expect(currentRow.getByRole("button")).toHaveCount(0);

  // 强制下线其一：恰有一个上下文失效，另一个不受影响
  await ap.getByLabel("用户关键字").fill(user.username);
  await ap.getByRole("button", { name: "搜索" }).click();
  await expect(userRows).toHaveCount(2);
  await userRows.first().getByRole("button", { name: "强制下线" }).click();
  await ap.getByRole("dialog").getByRole("button", { name: "确认下线" }).click();
  await expect(ap.getByText("已强制下线该会话")).toBeVisible();
  await expect(userRows).toHaveCount(1);

  const states = [await sessionState(first.page), await sessionState(second.page)].sort();
  expect(states).toEqual(["revoked", "valid"]);

  // 下线该用户全部会话：剩下的也失效
  await userRows.first().getByRole("button", { name: "下线该用户全部会话" }).click();
  const dialog = ap.getByRole("dialog");
  await expect(dialog.getByText(new RegExp(user.displayName))).toBeVisible();
  await dialog.getByRole("button", { name: "确认下线" }).click();
  await expect(ap.getByText("已下线该用户的全部会话")).toBeVisible();
  await expect(userRows).toHaveCount(0);
  expect(await sessionState(first.page)).toBe("revoked");
  expect(await sessionState(second.page)).toBe("revoked");

  // 管理员自己的会话不受影响
  await ap.reload();
  await expect(ap.getByRole("heading", { name: "会话管理" })).toBeVisible();

  for (const c of [admin, first, second]) await c.context.close();
});

test("admin cannot revoke their own current session through the API (409)", async ({ browser }) => {
  const admin = await newPage(browser);
  const ap = admin.page;
  await loginAsAdmin(ap);
  await ap.goto("/sessions");
  const listed = await ap.evaluate(async () => {
    const res = await fetch("/api/v1/sessions?size=100");
    return (await res.json()) as { items: { id: string; current: boolean }[] };
  });
  const own = listed.items.find((s) => s.current)!;
  const status = await ap.evaluate(
    async (id) => (await fetch(`/api/v1/sessions/${id}`, { method: "DELETE" })).status,
    own.id,
  );
  expect(status).toBe(409);
  await admin.context.close();
});
