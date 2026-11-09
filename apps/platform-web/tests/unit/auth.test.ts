// @vitest-environment node
import RedisMock from 'ioredis-mock';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createSessionStore } from '@/server/session';
import { PasswordChangeRequiredError, UnauthorizedError } from '@/server/tenantCoreClient';

const state = vi.hoisted(() => ({
  cookie: undefined as string | undefined,
  path: undefined as string | undefined,
  store: undefined as unknown,
  client: {} as Record<string, unknown>,
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
  headers: async () => ({
    get: (name: string) => (name === 'x-next-path' ? (state.path ?? null) : null),
  }),
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

import { requireSession, runWithSession } from '@/server/auth';

const login = { token: 'tk_abc', expiresAt: '2030-01-01T00:30:00Z', mustChangePassword: false };

async function redirectTarget(promise: Promise<unknown>): Promise<string> {
  const error = await promise.catch((e: unknown) => e);
  expect(error).toBeInstanceOf(RedirectSignal);
  return (error as RedirectSignal).target;
}

describe('requireSession', () => {
  let store: ReturnType<typeof createSessionStore>;

  beforeEach(async () => {
    const redis = new RedisMock();
    await redis.flushall();
    store = createSessionStore(redis as never, () => Date.parse('2030-01-01T00:00:00Z'));
    state.store = store;
    state.cookie = undefined;
    state.path = '/tenants';
    state.client = {};
  });

  it('redirects to /login?next=<path> when the cookie is missing', async () => {
    state.path = '/users?page=1';
    expect(await redirectTarget(requireSession())).toBe('/login?next=%2Fusers%3Fpage%3D1');
  });

  it('falls back to /tenants for next when the path header is absent', async () => {
    state.path = undefined;
    expect(await redirectTarget(requireSession())).toBe('/login?next=%2Ftenants');
  });

  it('routes an unknown or expired Session through /session/expired', async () => {
    state.cookie = 'ghost';
    expect(await redirectTarget(requireSession())).toBe('/session/expired?next=%2Ftenants');
  });

  it('redirects to /change-password when mustChangePassword is set', async () => {
    state.cookie = await store.create({ ...login, mustChangePassword: true });
    expect(await redirectTarget(requireSession())).toBe('/change-password');
  });

  it('lets the change-password page opt in to a mustChangePassword Session', async () => {
    state.cookie = await store.create({ ...login, mustChangePassword: true });
    await expect(requireSession({ allowMustChangePassword: true })).resolves.toEqual({
      sessionId: state.cookie,
      token: 'tk_abc',
      mustChangePassword: true,
    });
  });

  it('returns the Session for a valid cookie', async () => {
    state.cookie = await store.create(login);
    await expect(requireSession()).resolves.toMatchObject({ token: 'tk_abc' });
  });
});

describe('runWithSession', () => {
  let store: ReturnType<typeof createSessionStore>;

  beforeEach(async () => {
    const redis = new RedisMock();
    await redis.flushall();
    store = createSessionStore(redis as never, () => Date.parse('2030-01-01T00:00:00Z'));
    state.store = store;
    state.path = '/tenants';
    state.cookie = await store.create(login);
    state.client = {};
  });

  it('calls the callback with the client and the Opaque Token', async () => {
    const result = await runWithSession(async (client, session) => ({
      client,
      token: session.token,
    }));
    expect(result.token).toBe('tk_abc');
    expect(result.client).toBe(state.client);
  });

  it('destroys the Session and redirects to login when the backend answers 401', async () => {
    const id = state.cookie!;
    const target = await redirectTarget(
      runWithSession(async () => {
        throw new UnauthorizedError();
      }),
    );
    expect(target).toBe('/session/expired?next=%2Ftenants');
    await expect(store.get(id)).resolves.toBeNull();
  });

  it('redirects to /change-password when the backend answers PASSWORD_CHANGE_REQUIRED', async () => {
    const target = await redirectTarget(
      runWithSession(async () => {
        throw new PasswordChangeRequiredError();
      }),
    );
    expect(target).toBe('/change-password');
  });

  it('does not redirect PASSWORD_CHANGE_REQUIRED when the caller is the change-password flow', async () => {
    const error = new PasswordChangeRequiredError();
    await expect(
      runWithSession(
        async () => {
          throw error;
        },
        { allowMustChangePassword: true },
      ),
    ).rejects.toBe(error);
  });

  it('rethrows other errors untouched', async () => {
    const boom = new Error('boom');
    await expect(
      runWithSession(async () => {
        throw boom;
      }),
    ).rejects.toBe(boom);
  });
});
