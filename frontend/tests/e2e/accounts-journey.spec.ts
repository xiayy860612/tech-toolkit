import { expect, test, type Page } from "@playwright/test";
import {
  NEW_PASSWORD,
  changePassword,
  createUser,
  firstLogin,
  formAlert,
  login,
  loginAsAdmin,
  newPage,
  uniqueName,
} from "./helpers";

const accountRow = (page: Page, identifier: string) =>
  page.getByRole("row").filter({ hasText: identifier });

test("multiple accounts, last-account confirmation, reset password (quickstart 7, 8, 9, 14)", async ({
  browser,
}) => {
  const admin = await newPage(browser);
  const ap = admin.page;
  await loginAsAdmin(ap);

  // 用户 U 与第一个 Account；U 先登录并保持会话 S
  const user = await createUser(ap, { role: "MEMBER", prefix: "e2e-acc" });
  const sessionS = await newPage(browser);
  await firstLogin(sessionS.page, user);
  await expect(sessionS.page).toHaveURL(/\/forbidden/);

  // 管理员为同一用户新增第二个 Account
  await ap.goto(`/users/${user.id}`);
  await expect(ap.getByRole("heading", { name: user.displayName })).toBeVisible();
  const second = uniqueName("e2e-sec");
  await ap.getByRole("button", { name: "新增 Account" }).click();
  const addDialog = ap.getByRole("dialog", { name: "新增 Account" });
  await addDialog.getByLabel(/登录用户名/).fill(second);
  await addDialog.getByRole("button", { name: "创建" }).click();
  const shown = ap.getByRole("dialog", { name: /临时密码/ });
  const secondTemp = (await shown.locator("code").innerText()).trim();
  await shown.getByRole("button", { name: "我已记下，关闭" }).click();
  await expect(accountRow(ap, second)).toBeVisible();

  // 两个 Account 都能登录，且识别为同一个用户（同一显示名）
  const viaSecond = await newPage(browser);
  await login(viaSecond.page, second, secondTemp);
  await changePassword(viaSecond.page, secondTemp, NEW_PASSWORD);
  await expect(viaSecond.page).toHaveURL(/\/forbidden/);
  await viaSecond.page.getByRole("button", { name: new RegExp(user.displayName) }).waitFor();

  // 停用第二个 Account：不影响第一个；第二个无法再登录
  await accountRow(ap, second).getByRole("button", { name: "停用" }).click();
  await expect(accountRow(ap, second).getByText("停用", { exact: true }).first()).toBeVisible();
  const blocked = await newPage(browser);
  await login(blocked.page, second, NEW_PASSWORD);
  await expect(formAlert(blocked.page)).toHaveText("用户名或密码错误");
  await sessionS.page.reload();
  await expect(sessionS.page).toHaveURL(/\/forbidden/); // 第一个 Account 的会话不受影响

  // 停用最后一个可用 Account：先弹确认，取消不生效，确认才生效
  await accountRow(ap, user.username).getByRole("button", { name: "停用" }).click();
  const confirm = ap.getByRole("dialog");
  await expect(confirm.getByText(/最后一个可用/)).toBeVisible();
  await confirm.getByRole("button", { name: "取消" }).click();
  await expect(accountRow(ap, user.username).getByRole("button", { name: "停用" })).toBeVisible();
  await accountRow(ap, user.username).getByRole("button", { name: "停用" }).click();
  await ap.getByRole("dialog").getByRole("button", { name: "确认停用" }).click();
  await expect(accountRow(ap, user.username).getByRole("button", { name: "启用" })).toBeVisible();
  // 其已有会话立即失效
  await sessionS.page.reload();
  await expect(sessionS.page).toHaveURL(/\/login/);

  // 重新启用后重置密码：旧密码失效、原会话失效、再次强制改密
  await accountRow(ap, user.username).getByRole("button", { name: "启用" }).click();
  await expect(accountRow(ap, user.username).getByRole("button", { name: "停用" })).toBeVisible();
  const again = await newPage(browser);
  await login(again.page, user.username, NEW_PASSWORD);
  await expect(again.page).toHaveURL(/\/forbidden/); // 会话 S2

  await accountRow(ap, user.username).getByRole("button", { name: "重置密码" }).click();
  await ap.getByRole("dialog").getByRole("button", { name: "确认重置" }).click();
  const resetDialog = ap.getByRole("dialog", { name: /临时密码/ });
  const resetTemp = (await resetDialog.locator("code").innerText()).trim();
  await resetDialog.getByRole("button", { name: "我已记下，关闭" }).click();

  await again.page.reload();
  await expect(again.page).toHaveURL(/\/login/); // 场景 14：重置后原会话下一次请求即 401
  const old = await newPage(browser);
  await login(old.page, user.username, NEW_PASSWORD);
  await expect(formAlert(old.page)).toHaveText("用户名或密码错误"); // 旧密码失效
  const fresh = await newPage(browser);
  await login(fresh.page, user.username, resetTemp);
  await expect(fresh.page).toHaveURL(/\/change-password/); // 下次登录强制改密

  for (const c of [admin, sessionS, viaSecond, blocked, again, old, fresh]) await c.context.close();
});
