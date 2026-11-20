import { expect, test } from "@playwright/test";
import {
  NEW_PASSWORD,
  createUser,
  firstLogin,
  formAlert,
  login,
  loginAsAdmin,
  newPage,
} from "./helpers";

test("built-in username/password: reset, lock and unlock (quickstart 4, 7, 8, 9, 14)", async ({
  browser,
}) => {
  const admin = await newPage(browser);
  const ap = admin.page;
  await loginAsAdmin(ap);

  // 用户 U 先登录并保持会话 S
  const user = await createUser(ap, { role: "MEMBER", prefix: "e2e-cred" });
  const sessionS = await newPage(browser);
  await firstLogin(sessionS.page, user);
  await expect(sessionS.page).toHaveURL(/\/forbidden/);

  // 用户 id 是整数；会话 id 是 UUID（FR-004a）
  const ids = await ap.evaluate(async (id) => {
    const u = await (await fetch(`/api/v1/users/${id}`)).json();
    const s = await (await fetch("/api/v1/sessions?size=100")).json();
    return { userId: u.id as unknown, sessionId: s.items[0].id as string };
  }, user.id);
  expect(Number.isInteger(ids.userId)).toBe(true);
  expect(ids.sessionId).toMatch(/^[0-9a-f-]{36}$/);

  // 详情页「登录账号」区：用户名密码内置于 User，没有 Account 列表、「新增 Account」、停用与删除
  await ap.goto(`/users/${user.id}`);
  await expect(ap.getByRole("heading", { name: user.displayName })).toBeVisible();
  const row = ap.getByRole("row").filter({ hasText: user.username });
  await expect(row.getByText("用户名密码")).toBeVisible();
  await expect(ap.getByRole("button", { name: "新增 Account" })).toHaveCount(0);
  await expect(row.getByRole("button", { name: "停用" })).toHaveCount(0);
  await expect(row.getByRole("button", { name: "删除" })).toHaveCount(0);
  await expect(row.getByRole("button", { name: "解锁" })).toHaveCount(0);

  // 连续输错 5 次 → 锁定；锁定期间正确密码也被拒（统一提示）
  for (let i = 0; i < 5; i++) {
    const bad = await newPage(browser);
    await login(bad.page, user.username, `Wrong-${i}-Passw0rd!`);
    await expect(formAlert(bad.page)).toHaveText("用户名或密码错误");
    await bad.context.close();
  }
  const whileLocked = await newPage(browser);
  await login(whileLocked.page, user.username, NEW_PASSWORD);
  await expect(formAlert(whileLocked.page)).toHaveText("用户名或密码错误");

  // 管理员看到锁定标记并解锁，用户随即可以登录
  await ap.reload();
  await expect(ap.getByText(/已锁定至/)).toBeVisible();
  await ap
    .getByRole("row")
    .filter({ hasText: user.username })
    .getByRole("button", { name: "解锁" })
    .click();
  await expect(ap.getByText(/已锁定至/)).toHaveCount(0);
  const afterUnlock = await newPage(browser);
  await login(afterUnlock.page, user.username, NEW_PASSWORD);
  await expect(afterUnlock.page).toHaveURL(/\/forbidden/);

  // 重置密码：旧密码失效、原会话失效、再次强制改密
  await ap.getByRole("button", { name: "重置密码" }).click();
  await ap.getByRole("dialog").getByRole("button", { name: "确认重置" }).click();
  const resetDialog = ap.getByRole("dialog", { name: /临时密码/ });
  const resetTemp = (await resetDialog.locator("code").innerText()).trim();
  await resetDialog.getByRole("button", { name: "我已记下，关闭" }).click();

  await sessionS.page.reload();
  await expect(sessionS.page).toHaveURL(/\/login/); // 场景 14：重置后原会话下一次请求即 401
  const old = await newPage(browser);
  await login(old.page, user.username, NEW_PASSWORD);
  await expect(formAlert(old.page)).toHaveText("用户名或密码错误"); // 旧密码失效
  const fresh = await newPage(browser);
  await login(fresh.page, user.username, resetTemp);
  await expect(fresh.page).toHaveURL(/\/change-password/); // 下次登录强制改密

  for (const c of [admin, sessionS, whileLocked, afterUnlock, old, fresh]) await c.context.close();
});

test("an administrator cannot lock themselves out (quickstart 11, FR-021a)", async ({
  browser,
}) => {
  const admin = await newPage(browser);
  const ap = admin.page;
  await loginAsAdmin(ap);

  const me = await ap.evaluate(async () => {
    const res = await fetch("/api/v1/auth/me");
    return (await res.json()) as { userId: number };
  });
  expect(Number.isInteger(me.userId)).toBe(true);

  // 列表页：自己的那一行不展示「停用」，其他用户仍有
  await ap.goto("/users");
  const myRow = ap.getByRole("row").filter({ hasText: "admin" }).first();
  await expect(myRow.getByRole("button", { name: "停用" })).toHaveCount(0);

  // 详情页：对自己不展示停用开关；可以重置自己的密码
  await ap.goto(`/users/${me.userId}`);
  await expect(ap.getByRole("switch", { name: "启用状态" })).toHaveCount(0);
  await expect(ap.getByRole("button", { name: "重置密码" })).toBeVisible();

  // 编辑页：角色选择被禁用
  await ap.goto(`/users/${me.userId}/edit`);
  await expect(ap.getByText("不能修改自己的角色")).toBeVisible();
  for (const radio of await ap.getByRole("radio").all()) await expect(radio).toBeDisabled();

  // 后端兜底：绕过界面直接调用接口同样 409
  const disableSelf = await ap.evaluate(async (userId) => {
    const res = await fetch(`/api/v1/users/${userId}/disable`, { method: "POST" });
    return { status: res.status, code: (await res.json()).code as string };
  }, me.userId);
  expect(disableSelf).toEqual({ status: 409, code: "CANNOT_DISABLE_SELF" });

  await admin.context.close();
});
