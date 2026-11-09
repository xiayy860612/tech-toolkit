import { expect, test } from '@playwright/test';
import {
  HAPPY_PATH,
  VERSION_1_0_0,
  bootstrapTenant,
  e2eConfig,
  loginToConsole,
  logoutViaUserMenu,
  openUserMenu,
} from './fixtures';

// Requires the full stack, see fixtures.ts. Covers FR-026, FR-027, FR-028, SC-010.
const config = e2eConfig();

const VIEWPORTS = {
  phone: { width: 360, height: 740 },
  tablet: { width: 768, height: 1024 },
  desktop: { width: 1280, height: 800 },
} as const;

test.beforeAll(() => {
  // Idempotent: reuses the data created by the other specs when they already ran.
  bootstrapTenant(config);
});

test.describe('1.0.0', { tag: VERSION_1_0_0 }, () => {
  for (const [name, viewport] of Object.entries(VIEWPORTS)) {
    test.describe(`top bar and user menu at ${name} ${viewport.width}px`, () => {
      test.use({ viewport });

      test(
        'the top bar stays visible with the product name and a user menu showing who is logged in',
        { tag: HAPPY_PATH },
        async ({ page }) => {
          await loginToConsole(page, config);
          const banner = page.getByRole('banner');
          await expect(banner.getByText('Platform')).toBeVisible();

          await openUserMenu(page);
          const menu = page.getByRole('menu');
          await expect(menu.getByText('Admin User')).toBeVisible();
          await expect(menu.getByText(config.username, { exact: true })).toBeVisible();
          await expect(menu.getByText(config.tenantSlug, { exact: true })).toBeVisible();

          const overflow = await page.evaluate(
            () => document.documentElement.scrollWidth - window.innerWidth,
          );
          expect(overflow).toBeLessThanOrEqual(0);
        },
      );

      test(
        '退出登录 exists only in the user menu and logs out',
        { tag: HAPPY_PATH },
        async ({ page }) => {
          await loginToConsole(page, config);
          // Closed menu: no 退出登录 anywhere (sidebar and Sheet no longer carry it).
          await expect(page.getByRole('button', { name: '退出登录' })).toHaveCount(0);

          await logoutViaUserMenu(page);
          await expect(page).toHaveURL(/\/login/);
        },
      );

      test('every list page has a page title and no write controls', async ({ page }) => {
        await loginToConsole(page, config);
        for (const [path, title] of [
          ['/tenants', '租户管理'],
          ['/modules', '模块管理'],
          ['/users', '用户管理'],
        ]) {
          await page.goto(path);
          await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
          await expect(
            page.getByRole('main').getByRole('button', { name: /新建|创建|删除|编辑|订阅/ }),
          ).toHaveCount(0);
        }
      });
    });
  }
});
