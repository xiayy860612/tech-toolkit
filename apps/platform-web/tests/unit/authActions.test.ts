// @vitest-environment node
import RedisMock from 'ioredis-mock';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createSessionStore } from '@/server/session';
import { ApiError, UnauthorizedError } from '@/server/tenantCoreClient';

const state = vi.hoisted(() => ({
  cookie: undefined as string | undefined,
  store: undefined as unknown,
  client: {
    login: vi.fn(),
    changePassword: vi.fn(),
  },
  setSessionCookie: vi.fn(),
}));

class RedirectSignal extends Error {
  constructor(readonly target: string) {
    super(`redirect:${target}`);
  }
}

vi.mock('next/headers', () => ({
  cookies: async () => ({
    get: (name: string) =>
      name === 'platform_sid' && state.cookie ? { name, value: state.cookie } : undefined,
  }),
  headers: async () => ({ get: () => null }),
}));
vi.mock('next/navigation', () => ({
  redirect: (target: string) => {
    throw new RedirectSignal(target);
  },
}));
vi.mock('@/server/container', () => ({
  getSessionStore: () => state.store,
  getTenantCoreClient: () => state.client,
}));
vi.mock('@/server/sessionCookie', () => ({
  SESSION_COOKIE: 'platform_sid',
  setSessionCookie: state.setSessionCookie,
}));

import { changePasswordAction } from '@/app/change-password/actions';
import { loginAction } from '@/app/login/actions';

const loginResponse = {
  token: 'tk_new',
  expiresAt: '2030-01-01T00:30:00Z',
  mustChangePassword: false,
};

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(values)) data.set(key, value);
  return data;
}

async function redirectTarget(promise: Promise<unknown>): Promise<string> {
  const error = await promise.catch((e: unknown) => e);
  expect(error).toBeInstanceOf(RedirectSignal);
  return (error as RedirectSignal).target;
}

let store: ReturnType<typeof createSessionStore>;

beforeEach(async () => {
  const redis = new RedisMock();
  await redis.flushall();
  store = createSessionStore(redis as never, () => Date.parse('2030-01-01T00:00:00Z'));
  state.store = store;
  state.cookie = undefined;
  state.client.login.mockReset();
  state.client.changePassword.mockReset();
  state.setSessionCookie.mockReset();
});

describe('loginAction', () => {
  const credentials = { tenantSlug: 'acme', username: 'admin', password: 'pw-1' };

  it('creates a Session, sets the cookie and redirects to next', async () => {
    state.client.login.mockResolvedValue(loginResponse);

    const target = await redirectTarget(
      loginAction({}, form({ ...credentials, next: '/users?page=1' })),
    );

    expect(target).toBe('/users?page=1');
    expect(state.client.login).toHaveBeenCalledWith(credentials);
    expect(state.setSessionCookie).toHaveBeenCalledOnce();
    const [sessionId, expiresAt] = state.setSessionCookie.mock.calls[0];
    expect(expiresAt).toBe(loginResponse.expiresAt);
    await expect(store.get(sessionId)).resolves.toMatchObject({
      token: 'tk_new',
      mustChangePassword: false,
    });
  });

  it('falls back to /tenants for an unsafe next', async () => {
    state.client.login.mockResolvedValue(loginResponse);
    const target = await redirectTarget(
      loginAction({}, form({ ...credentials, next: '//evil.example' })),
    );
    expect(target).toBe('/tenants');
  });

  it('redirects to /change-password when the Account must change its password', async () => {
    state.client.login.mockResolvedValue({ ...loginResponse, mustChangePassword: true });
    const target = await redirectTarget(loginAction({}, form({ ...credentials, next: '/users' })));
    expect(target).toBe('/change-password');
  });

  it('trims the Tenant Slug and username', async () => {
    state.client.login.mockResolvedValue(loginResponse);
    await redirectTarget(
      loginAction({}, form({ tenantSlug: ' acme ', username: ' admin ', password: 'pw-1' })),
    );
    expect(state.client.login).toHaveBeenCalledWith(credentials);
  });

  it('returns INVALID_CREDENTIALS for incomplete input without calling the backend', async () => {
    await expect(loginAction({}, form({ ...credentials, password: '' }))).resolves.toMatchObject({
      error: 'INVALID_CREDENTIALS',
    });
    expect(state.client.login).not.toHaveBeenCalled();
  });

  it('returns INVALID_CREDENTIALS for wrong credentials', async () => {
    state.client.login.mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', 'x'));
    await expect(loginAction({}, form(credentials))).resolves.toMatchObject({
      error: 'INVALID_CREDENTIALS',
    });
    expect(state.setSessionCookie).not.toHaveBeenCalled();
  });

  it('returns MODULE_NOT_SUBSCRIBED when the Tenant has no platform subscription', async () => {
    state.client.login.mockRejectedValue(new ApiError(403, 'MODULE_NOT_SUBSCRIBED', 'x'));
    await expect(loginAction({}, form(credentials))).resolves.toMatchObject({
      error: 'MODULE_NOT_SUBSCRIBED',
    });
  });

  it('echoes the Tenant Slug and username (never the password) so the form can refill', async () => {
    state.client.login.mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', 'x'));
    const result = await loginAction({}, form(credentials));
    expect(result).toEqual({ error: 'INVALID_CREDENTIALS', tenantSlug: 'acme', username: 'admin' });
  });

  it('returns UNAVAILABLE for other failures', async () => {
    state.client.login.mockRejectedValue(new Error('network down'));
    await expect(loginAction({}, form(credentials))).resolves.toMatchObject({
      error: 'UNAVAILABLE',
    });
  });
});

describe('changePasswordAction', () => {
  const values = { currentPassword: 'old-pw', newPassword: 'new-pw', confirmPassword: 'new-pw' };

  async function loggedIn(mustChangePassword = true) {
    state.cookie = await store.create({
      token: 'tk_old',
      expiresAt: '2030-01-01T00:30:00Z',
      mustChangePassword,
    });
  }

  it('replaces the stored Opaque Token with the new one and goes to /tenants', async () => {
    await loggedIn();
    state.client.changePassword.mockResolvedValue(loginResponse);

    const target = await redirectTarget(changePasswordAction({}, form(values)));

    expect(target).toBe('/tenants');
    expect(state.client.changePassword).toHaveBeenCalledWith('tk_old', {
      currentPassword: 'old-pw',
      newPassword: 'new-pw',
    });
    await expect(store.get(state.cookie!)).resolves.toEqual({
      token: 'tk_new',
      mustChangePassword: false,
    });
    expect(state.setSessionCookie).toHaveBeenCalledWith(state.cookie, loginResponse.expiresAt);
  });

  it('rejects mismatching confirmation without calling the backend', async () => {
    await loggedIn();
    await expect(
      changePasswordAction({}, form({ ...values, confirmPassword: 'other' })),
    ).resolves.toEqual({ error: 'MISMATCH' });
    expect(state.client.changePassword).not.toHaveBeenCalled();
  });

  it('maps a wrong current password to INVALID_CURRENT_PASSWORD', async () => {
    await loggedIn();
    state.client.changePassword.mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', 'x'));
    await expect(changePasswordAction({}, form(values))).resolves.toEqual({
      error: 'INVALID_CURRENT_PASSWORD',
    });
  });

  it('surfaces the backend password policy message', async () => {
    await loggedIn();
    state.client.changePassword.mockRejectedValue(
      new ApiError(400, 'PASSWORD_POLICY', 'at least 12 characters'),
    );
    await expect(changePasswordAction({}, form(values))).resolves.toEqual({
      error: 'PASSWORD_POLICY',
      detail: 'at least 12 characters',
    });
  });

  it('returns UNAVAILABLE for other failures', async () => {
    await loggedIn();
    state.client.changePassword.mockRejectedValue(new Error('boom'));
    await expect(changePasswordAction({}, form(values))).resolves.toEqual({
      error: 'UNAVAILABLE',
    });
  });

  it('returns to login when the backend no longer accepts the Opaque Token', async () => {
    await loggedIn();
    state.client.changePassword.mockRejectedValue(new UnauthorizedError());
    const target = await redirectTarget(changePasswordAction({}, form(values)));
    expect(target).toBe('/session/expired?next=%2Ftenants');
    await expect(store.get(state.cookie!)).resolves.toBeNull();
  });

  it('requires a Session', async () => {
    const target = await redirectTarget(changePasswordAction({}, form(values)));
    expect(target).toContain('/login');
  });
});
