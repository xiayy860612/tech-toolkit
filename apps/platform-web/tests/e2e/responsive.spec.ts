import { expect, test, type Page } from '@playwright/test';
import { bootstrapTenant, e2eConfig, loginToConsole } from './fixtures';

// Requires the full stack, see fixtures.ts. Covers FR-024 / SC-009.
const config = e2eConfig();

const VIEWPORTS = {
  phone: { width: 360, height: 740 },
  tablet: { width: 768, height: 1024 },
  desktop: { width: 1280, height: 800 },
} as const;

const LIST_PATHS = ['/tenants', '/modules', '/users'] as const;
const NAV_LABELS = ['租户管理', '模块管理', '用户管理'];

async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
}

test.beforeAll(() => {
  // Idempotent: reuses the data created by the other specs when they already ran.
  bootstrapTenant(config);
});

for (const [name, viewport] of Object.entries(VIEWPORTS)) {
  test.describe(`${name} ${viewport.width}x${viewport.height}`, () => {
    test.use({ viewport });

    test('login page has no horizontal scroll and 44px controls', async ({ page }) => {
      await page.goto('/login');
      await expect(page.getByRole('heading', { name: '登录' })).toBeVisible();
      await expectNoHorizontalScroll(page);
      for (const control of [
        page.getByLabel('租户标识'),
        page.getByLabel('用户名'),
        page.getByLabel('密码', { exact: true }),
        page.getByRole('button', { name: '登录' }),
      ]) {
        const box = await control.boundingBox();
        expect(box?.height).toBeGreaterThanOrEqual(44);
      }
    });

    test('change-password page has no horizontal scroll', async ({ page }) => {
      await loginToConsole(page, config);
      await page.goto('/change-password');
      await expect(page.getByLabel('确认新密码')).toBeVisible();
      await expectNoHorizontalScroll(page);
    });

    for (const path of LIST_PATHS) {
      test(`${path} has no horizontal scroll`, async ({ page }) => {
        await loginToConsole(page, config);
        await page.goto(path);
        await expect(page.getByRole('main').getByRole('heading')).toBeVisible();
        await expectNoHorizontalScroll(page);
      });
    }

    test('lists use cards below 640px and a table from 640px', async ({ page }) => {
      await loginToConsole(page, config);
      await page.goto('/tenants');
      const table = page.getByRole('table');
      const cards = page.getByRole('main').getByRole('listitem');
      if (viewport.width < 640) {
        await expect(cards.first()).toBeVisible();
        await expect(table).toBeHidden();
      } else {
        await expect(table).toBeVisible();
        await expect(cards.first()).toBeHidden();
      }
    });
  });
}

test.describe('narrow screen navigation (360x740)', () => {
  test.use({ viewport: VIEWPORTS.phone });

  test('the menu button opens a Sheet with the three items and logout, and navigates', async ({
    page,
  }) => {
    await loginToConsole(page, config);
    // Only one 主导航 is exposed: the sidebar is display:none, the Sheet is closed.
    await expect(page.getByRole('navigation', { name: '主导航' })).toHaveCount(0);

    await page.getByRole('button', { name: '打开菜单' }).click();
    const dialog = page.getByRole('dialog');
    const navigation = dialog.getByRole('navigation', { name: '主导航' });
    await expect(navigation.getByRole('link')).toHaveText(NAV_LABELS);
    await expect(dialog.getByRole('button', { name: '退出登录' })).toBeVisible();

    await navigation.getByRole('link', { name: '用户管理' }).click();
    await expect(page).toHaveURL(/\/users/);
    await expect(page.getByRole('dialog')).toBeHidden();

    await page.getByRole('button', { name: '打开菜单' }).click();
    await expect(page.getByRole('dialog').getByRole('link', { name: '用户管理' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  test('logout from the menu returns to the login page', async ({ page }) => {
    await loginToConsole(page, config);
    await page.getByRole('button', { name: '打开菜单' }).click();
    await page.getByRole('dialog').getByRole('button', { name: '退出登录' }).click();
    await expect(page).toHaveURL(/\/login/);
  });
});

for (const [name, viewport] of [
  ['tablet', VIEWPORTS.tablet],
  ['desktop', VIEWPORTS.desktop],
] as const) {
  test.describe(`sidebar and table at ${name} ${viewport.width}px`, () => {
    test.use({ viewport });

    test('shows the sidebar and the table, without the menu button', async ({ page }) => {
      await loginToConsole(page, config);
      await page.goto('/tenants');
      const navigation = page.getByRole('navigation', { name: '主导航' });
      await expect(navigation).toBeVisible();
      await expect(navigation.getByRole('link')).toHaveText(NAV_LABELS);
      await expect(page.getByRole('table')).toBeVisible();
      await expect(page.getByRole('button', { name: '打开菜单' })).toBeHidden();
    });
  });
}
