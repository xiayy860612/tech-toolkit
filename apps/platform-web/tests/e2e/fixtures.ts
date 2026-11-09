/**
 * E2E fixtures for platform-web.
 *
 * Requires a running stack: Postgres + Redis + tenant-core-api + platform-web, plus the built
 * tenant-core-cli (backend task T035). All data is created through the Bootstrap CLI, exactly like
 * a real operator would (the CLI talks to the database directly, there is no write API).
 *
 * Environment (see apps/platform-web/.env.example):
 *   E2E_BASE_URL          platform-web URL (default http://localhost:3000)
 *   TENANT_CORE_CLI       path of the tenant-core-cli executable (may include fixed leading
 *                         arguments, e.g. "java -jar tenant-core-cli.jar"); the CLI reads its
 *                         database settings from its own environment
 *   E2E_TENANT_SLUG       Tenant Slug to bootstrap
 *   E2E_USERNAME          username of the bootstrap Account
 *   E2E_INITIAL_PASSWORD  initial password given to the CLI (must satisfy the 12-128 char rule)
 *   E2E_NEW_PASSWORD      password chosen at the forced first-login change
 *   REDIS_URL             Redis of the BFF (only used to revoke a Session's Opaque Token)
 *   TENANT_CORE_API_URL   tenant-core-api (only used to revoke that Opaque Token)
 *
 * The journey changes the Account password, so run it against a fresh database or use a new
 * E2E_TENANT_SLUG per run. `loginToConsole` tolerates an already changed password.
 */
import { expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { Redis } from 'ioredis';

export const INVALID_CREDENTIALS_MESSAGE = '用户名或密码错误，如多次失败请稍后再试';
export const NO_ACCESS_MESSAGE = '无访问权限';

export interface E2eConfig {
  tenantSlug: string;
  username: string;
  initialPassword: string;
  newPassword: string;
}

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required environment variable ${name}`);
  return value;
}

export function e2eConfig(): E2eConfig {
  return {
    tenantSlug: required('E2E_TENANT_SLUG'),
    username: required('E2E_USERNAME'),
    initialPassword: required('E2E_INITIAL_PASSWORD'),
    newPassword: required('E2E_NEW_PASSWORD'),
  };
}

/** Runs tenant-core-cli with `--output json` and returns the parsed single-line result. */
export function cli(args: string[], stdin?: string): Record<string, string> {
  const [command, ...fixedArgs] = required('TENANT_CORE_CLI').split(/\s+/);
  const stdout = execFileSync(command, [...fixedArgs, '--output', 'json', ...args], {
    input: stdin,
    encoding: 'utf8',
  });
  return JSON.parse(stdout.trim().split('\n').at(-1) ?? '{}') as Record<string, string>;
}

export function createUser(tenantSlug: string, displayName: string): string {
  const result = cli(['user', 'create', '--tenant', tenantSlug, '--display-name', displayName]);
  return result.tenantUserId;
}

/** Tenant + password Login Method + platform subscription + User "Admin User" with an Account. */
export function bootstrapTenant(config: E2eConfig = e2eConfig()): { tenantUserId: string } {
  const { tenantSlug, username, initialPassword } = config;
  cli(['tenant', 'create', '--slug', tenantSlug, '--name', `${tenantSlug} Inc`]);
  cli(['login-method', 'enable', '--tenant', tenantSlug, '--type', 'password']);
  const tenantUserId = createUser(tenantSlug, 'Admin User');
  cli(
    [
      'account',
      'create',
      '--tenant',
      tenantSlug,
      '--user',
      tenantUserId,
      '--username',
      username,
      '--password-stdin',
    ],
    `${initialPassword}\n`,
  );
  cli(['subscription', 'add', '--tenant', tenantSlug, '--module', 'platform']);
  return { tenantUserId };
}

export async function fillLogin(
  page: Page,
  tenantSlug: string,
  username: string,
  password: string,
) {
  await page.getByLabel('租户标识').fill(tenantSlug);
  await page.getByLabel('用户名').fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录' }).click();
}

/**
 * The form's error Alert. `getByRole('alert')` alone is ambiguous: Next.js renders its own
 * empty route announcer with role="alert" (outside `main`).
 */
export function loginError(page: Page) {
  return page.locator('main [role="alert"]');
}

export async function changePassword(page: Page, current: string, next: string) {
  await page.getByLabel('当前密码').fill(current);
  await page.getByLabel('新密码', { exact: true }).fill(next);
  await page.getByLabel('确认新密码').fill(next);
  await page.getByRole('button', { name: '修改密码' }).click();
}

/**
 * Logs in and lands on the console. Handles both a fresh Account (initial password, forced change)
 * and an Account whose password was already changed by a previous run.
 */
export async function loginToConsole(page: Page, config: E2eConfig = e2eConfig()) {
  await page.goto('/login');
  await fillLogin(page, config.tenantSlug, config.username, config.initialPassword);
  // Wait for the outcome of the submit: either we left /login, or the form shows its error.
  // (Waiting for a URL that still matches /login would return before the request finished.)
  await Promise.race([page.waitForURL(/\/(change-password|tenants)/), loginError(page).waitFor()]);

  if (page.url().includes('/login')) {
    // Initial password rejected: the password was changed in an earlier run.
    await fillLogin(page, config.tenantSlug, config.username, config.newPassword);
    await page.waitForURL(/\/tenants/);
  } else if (page.url().includes('/change-password')) {
    await changePassword(page, config.initialPassword, config.newPassword);
    await page.waitForURL(/\/tenants/);
  }
  // Wide screens show the sidebar; phones show only the menu button (the nav lives in the Sheet).
  await expect(
    page
      .getByRole('navigation', { name: '主导航' })
      .or(page.getByRole('button', { name: '打开菜单' })),
  ).toBeVisible();
}

/**
 * Revokes the Session's Opaque Token at tenant-core-api, behind the BFF's back
 * (what an operator or an idle timeout would do). The browser Session cookie stays valid.
 */
export async function revokeOpaqueTokenBehindTheBff(page: Page) {
  const cookie = (await page.context().cookies()).find((c) => c.name === 'platform_sid');
  if (!cookie) throw new Error('No Session cookie to revoke');

  const redis = new Redis(process.env.REDIS_URL ?? 'redis://localhost:6379');
  try {
    const raw = await redis.get(`platform:session:${cookie.value}`);
    if (!raw) throw new Error('Session not found in Redis');
    const { token } = JSON.parse(raw) as { token: string };
    const api = process.env.TENANT_CORE_API_URL ?? 'http://localhost:8080';
    const response = await fetch(`${api}/v1/auth/logout`, {
      method: 'POST',
      headers: { 'X-Session-Id': token },
    });
    expect(response.status).toBe(204);
  } finally {
    redis.disconnect();
  }
}

/** Release tag of this E2E set. Run one set with `pnpm e2e --tag 1.0.0`; no tag runs everything. */
export const VERSION_1_0_0 = '@1.0.0';

/** Opens the top-bar user menu (the trigger shows the User's Display Name, "Admin User" in bootstrapTenant). */
export async function openUserMenu(page: Page) {
  await page
    .getByRole('banner')
    .getByRole('button', { name: /Admin User/ })
    .click();
  await expect(page.getByRole('menu')).toBeVisible();
}

/** 退出登录 now lives only in the user menu. */
export async function logoutViaUserMenu(page: Page) {
  await openUserMenu(page);
  await page.getByRole('menuitem', { name: '退出登录' }).click();
}

/** Marks the main user journeys (login, first password change, lists, responsive nav, logout). Run with `--grep @happy-path`. */
export const HAPPY_PATH = '@happy-path';
