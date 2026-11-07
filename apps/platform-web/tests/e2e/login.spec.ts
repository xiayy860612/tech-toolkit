import { expect, test } from '@playwright/test';
import {
  INVALID_CREDENTIALS_MESSAGE,
  bootstrapTenant,
  changePassword,
  e2eConfig,
  fillLogin,
  logoutViaUserMenu,
  loginError,
  revokeOpaqueTokenBehindTheBff,
  VERSION_1_0_0,
  HAPPY_PATH,
} from './fixtures';

// The journey changes the Account password, so it gets its own Tenant: console.spec.ts (which runs
// first, alphabetically) must not use up the initial password of this one.
const base = e2eConfig();
const config = { ...base, tenantSlug: `${base.tenantSlug}-login` };

test.describe('1.0.0', { tag: VERSION_1_0_0 }, () => {
  // One ordered journey: the first login forces a password change and mutates the Account.
  test.describe.configure({ mode: 'serial' });

  test.beforeAll(() => {
    bootstrapTenant(config);
  });

  test('the three protected pages redirect to /login', async ({ page }) => {
    for (const path of ['/tenants', '/modules', '/users']) {
      await page.goto(path);
      await expect(page).toHaveURL(new RegExp(`/login\\?next=${encodeURIComponent(path)}$`));
    }
  });

  test('wrong password and unknown account give the same message', async ({ page }) => {
    // Exactly one failure per case: repeated failures would lock the Account for a while.
    await page.goto('/login');
    await fillLogin(page, config.tenantSlug, config.username, 'definitely-not-the-password');
    await expect(loginError(page)).toHaveText(INVALID_CREDENTIALS_MESSAGE);
    const wrongPassword = await loginError(page).textContent();

    await page.goto('/login');
    await fillLogin(page, config.tenantSlug, 'no-such-account', 'definitely-not-the-password');
    await expect(loginError(page)).toHaveText(INVALID_CREDENTIALS_MESSAGE);
    const unknownAccount = await loginError(page).textContent();

    expect(unknownAccount).toBe(wrongPassword);
  });

  test(
    'first login forces a password change, then reaches the app and returns to next',
    { tag: HAPPY_PATH },
    async ({ page }) => {
      await page.goto('/modules');
      await expect(page).toHaveURL(/\/login\?next=%2Fmodules$/);

      await fillLogin(page, config.tenantSlug, config.username, config.initialPassword);
      await expect(page).toHaveURL(/\/change-password$/);

      // Other pages bounce back to the change-password page until it is done.
      await page.goto('/users');
      await expect(page).toHaveURL(/\/change-password$/);

      await changePassword(page, config.initialPassword, config.newPassword);
      await expect(page).toHaveURL(/\/tenants$/);
      await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible();
    },
  );

  test(
    'logout returns to /login and protected pages redirect again',
    { tag: HAPPY_PATH },
    async ({ page }) => {
      await page.goto('/login');
      await fillLogin(page, config.tenantSlug, config.username, config.newPassword);
      await expect(page).toHaveURL(/\/tenants$/);

      await logoutViaUserMenu(page);
      await expect(page).toHaveURL(/\/login$/);

      await page.goto('/users');
      await expect(page).toHaveURL(/\/login\?next=%2Fusers$/);
    },
  );

  test(
    'a logged-in user visiting /login is sent to /tenants',
    { tag: HAPPY_PATH },
    async ({ page }) => {
      await page.goto('/login');
      await fillLogin(page, config.tenantSlug, config.username, config.newPassword);
      await expect(page).toHaveURL(/\/tenants$/);

      await page.goto('/login');
      await expect(page).toHaveURL(/\/tenants$/);
    },
  );

  test('a token revoked at the backend sends the next navigation back to /login', async ({
    page,
  }) => {
    await page.goto('/login');
    await fillLogin(page, config.tenantSlug, config.username, config.newPassword);
    await expect(page).toHaveURL(/\/tenants$/);

    await revokeOpaqueTokenBehindTheBff(page);

    await page.getByRole('link', { name: '用户管理' }).click();
    await expect(page).toHaveURL(/\/login\?next=%2Fusers$/);

    // The stale cookie was cleared, so protected pages keep redirecting (no loop).
    await page.goto('/tenants');
    await expect(page).toHaveURL(/\/login\?next=%2Ftenants$/);
  });
});
