import { expect, test } from '@playwright/test';
import { bootstrapTenant, createUser, e2eConfig, loginToConsole } from './fixtures';

const config = e2eConfig();
const USER_WITHOUT_ACCOUNT = 'No Account User';
const EXTRA_USER_COUNT = 6;
const SMALL_PAGE = 5;

test.beforeAll(() => {
  // Idempotent: reuses the data created by login.spec.ts when it already ran.
  bootstrapTenant(config);
  createUser(config.tenantSlug, USER_WITHOUT_ACCOUNT);
  for (let i = 1; i <= EXTRA_USER_COUNT; i += 1) {
    createUser(config.tenantSlug, `Extra User ${i}`);
  }
});

test.beforeEach(async ({ page }) => {
  await loginToConsole(page, config);
});

test('the sidebar has exactly the three items and highlights the current one', async ({ page }) => {
  const links = page.getByRole('navigation', { name: '主导航' }).getByRole('link');
  await expect(links).toHaveText(['租户管理', '模块管理', '用户管理']);

  await expect(page.getByRole('link', { name: '租户管理' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await page.getByRole('link', { name: '模块管理' }).click();
  await expect(page).toHaveURL(/\/modules/);
  await expect(page.getByRole('link', { name: '模块管理' })).toHaveAttribute(
    'aria-current',
    'page',
  );
});

test('the root path lands on 租户管理', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/tenants$/);
});

test('租户管理 shows the Tenant created by the CLI', async ({ page }) => {
  await page.goto('/tenants');
  // anchored: other runs and specs leave Tenants whose slug starts with the same text
  const row = page.getByRole('row', { name: new RegExp(`^${config.tenantSlug} `) });
  await expect(row).toContainText('正常');
  await expect(page.getByRole('columnheader')).toHaveText(['租户标识', '名称', '状态', '创建时间']);
});

test('模块管理 shows the platform Module Subscription', async ({ page }) => {
  await page.goto('/modules');
  const row = page.getByRole('row', {
    name: new RegExp(`^platform ${config.tenantSlug} 已订阅`),
  });
  await expect(row).toBeVisible();
});

test('用户管理 shows Users with their usernames, including a User without an Account', async ({
  page,
}) => {
  await page.goto(`/users?page=0&size=100`);

  const admin = page.getByRole('row', {
    name: new RegExp(`^Admin User ${config.username} ${config.tenantSlug}`),
  });
  await expect(admin.getByRole('cell').nth(1)).toHaveText(config.username);

  const noAccount = page.getByRole('row', {
    name: new RegExp(`^${USER_WITHOUT_ACCOUNT} — ${config.tenantSlug}`),
  });
  await expect(noAccount).toBeVisible();
  await expect(noAccount.getByRole('cell').nth(1)).toHaveText('—');
});

test('用户管理 paginates through searchParams', async ({ page }) => {
  await page.goto(`/users?page=0&size=${SMALL_PAGE}`);
  await expect(page.getByRole('row')).toHaveCount(SMALL_PAGE + 1); // header + rows
  await expect(page.getByText(/第 1 \/ \d+ 页/)).toBeVisible();
  await expect(page.getByText('上一页')).toHaveAttribute('aria-disabled', 'true');

  await page.getByRole('link', { name: '下一页' }).click();
  await expect(page).toHaveURL(new RegExp(`/users\\?page=1&size=${SMALL_PAGE}$`));
  await expect(page.getByText(/第 2 \/ \d+ 页/)).toBeVisible();
  await expect(page.getByRole('link', { name: '上一页' })).toBeVisible();
});

test('the list pages expose no write controls', async ({ page }) => {
  for (const path of ['/tenants', '/modules', '/users']) {
    await page.goto(path);
    const main = page.getByRole('main');
    await expect(main.getByRole('button')).toHaveCount(0);
    await expect(main.locator('form, input, select, textarea')).toHaveCount(0);
    // "创建时间" is a column header, not a create action
    await expect(main.getByText(/新增|编辑|删除|创建(?!时间)/)).toHaveCount(0);
    // Nothing credential-like on the page.
    await expect(main.getByText(/password|密码|token/i)).toHaveCount(0);
  }
});
