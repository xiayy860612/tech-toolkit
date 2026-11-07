import { describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  PasswordChangeRequiredError,
  UnauthorizedError,
  createTenantCoreClient,
} from '@/server/tenantCoreClient';

function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function setup(response: Response | (() => Response)) {
  const fetchMock = vi.fn(async () => (typeof response === 'function' ? response() : response));
  const client = createTenantCoreClient({
    baseUrl: 'http://api.test/',
    fetch: fetchMock as unknown as typeof fetch,
  });
  return { client, fetchMock };
}

function lastCall(fetchMock: ReturnType<typeof vi.fn>) {
  const [url, init] = fetchMock.mock.calls.at(-1) as unknown as [string, RequestInit];
  return { url, init, headers: new Headers(init.headers) };
}

describe('tenantCoreClient', () => {
  it('login posts tenantSlug/username/password without any session header', async () => {
    const body = { token: 'tk_abc', expiresAt: '2030-01-01T00:00:00Z', mustChangePassword: true };
    const { client, fetchMock } = setup(jsonResponse(200, body));

    const result = await client.login({ tenantSlug: 'acme', username: 'admin', password: 'pw' });

    const { url, init, headers } = lastCall(fetchMock);
    expect(result).toEqual(body);
    expect(url).toBe('http://api.test/v1/auth/login');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({
      tenantSlug: 'acme',
      username: 'admin',
      password: 'pw',
    });
    expect(headers.get('X-Session-Id')).toBeNull();
    expect(headers.get('Authorization')).toBeNull();
    expect(init.cache).toBe('no-store');
  });

  it('login maps 401 to ApiError INVALID_CREDENTIALS, never UnauthorizedError', async () => {
    const { client } = setup(jsonResponse(401, { code: 'INVALID_CREDENTIALS', message: 'x' }));
    await expect(
      client.login({ tenantSlug: 'a', username: 'b', password: 'c' }),
    ).rejects.toMatchObject({ name: 'ApiError', code: 'INVALID_CREDENTIALS', status: 401 });
  });

  it('login maps a 401 with an unreadable body to INVALID_CREDENTIALS', async () => {
    const { client } = setup(new Response('nope', { status: 401 }));
    await expect(
      client.login({ tenantSlug: 'a', username: 'b', password: 'c' }),
    ).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' });
  });

  it('login maps 403 to ApiError MODULE_NOT_SUBSCRIBED', async () => {
    const { client } = setup(jsonResponse(403, { code: 'MODULE_NOT_SUBSCRIBED', message: 'x' }));
    await expect(
      client.login({ tenantSlug: 'a', username: 'b', password: 'c' }),
    ).rejects.toMatchObject({ code: 'MODULE_NOT_SUBSCRIBED', status: 403 });
  });

  it('sends the Opaque Token in X-Session-Id and never in Authorization', async () => {
    const page = { items: [], page: 0, size: 20, total: 0 };
    const { client, fetchMock } = setup(jsonResponse(200, page));

    await client.listTenants('tk_secret', { page: 2, size: 50 });

    const { url, headers } = lastCall(fetchMock);
    expect(url).toBe('http://api.test/v1/tenants?page=2&size=50');
    expect(headers.get('X-Session-Id')).toBe('tk_secret');
    expect(headers.get('Authorization')).toBeNull();
  });

  it.each([
    ['listTenants', '/v1/tenants'],
    ['listModuleSubscriptions', '/v1/module-subscriptions'],
    ['listUsers', '/v1/users'],
  ] as const)('%s calls %s and returns the page without the token', async (method, path) => {
    const page = {
      items: [{ displayName: 'A' }],
      page: 0,
      size: 20,
      total: 1,
    };
    const { client, fetchMock } = setup(jsonResponse(200, page));

    const result = await client[method]('tk_secret', { page: 0, size: 20 });

    expect(lastCall(fetchMock).url).toContain(path);
    expect(result).toEqual(page);
    expect(JSON.stringify(result)).not.toContain('tk_secret');
  });

  it('me returns the current User', async () => {
    const me = {
      tenantUserId: 'tu_1',
      displayName: 'Zhang San',
      accountUsername: 'admin',
      tenantSlug: 'acme',
      mustChangePassword: false,
    };
    const { client, fetchMock } = setup(jsonResponse(200, me));
    await expect(client.me('tk')).resolves.toEqual(me);
    expect(lastCall(fetchMock).url).toBe('http://api.test/v1/auth/me');
  });

  it('logout posts with the token and tolerates 204', async () => {
    const { client, fetchMock } = setup(new Response(null, { status: 204 }));
    await expect(client.logout('tk')).resolves.toBeUndefined();
    const { url, init, headers } = lastCall(fetchMock);
    expect(url).toBe('http://api.test/v1/auth/logout');
    expect(init.method).toBe('POST');
    expect(headers.get('X-Session-Id')).toBe('tk');
  });

  it('changePassword posts currentPassword/newPassword and returns a new LoginResponse', async () => {
    const body = { token: 'tk_new', expiresAt: '2030-01-01T00:00:00Z', mustChangePassword: false };
    const { client, fetchMock } = setup(jsonResponse(200, body));

    await expect(
      client.changePassword('tk_old', { currentPassword: 'a', newPassword: 'b' }),
    ).resolves.toEqual(body);

    const { url, init, headers } = lastCall(fetchMock);
    expect(url).toBe('http://api.test/v1/auth/change-password');
    expect(JSON.parse(init.body as string)).toEqual({ currentPassword: 'a', newPassword: 'b' });
    expect(headers.get('X-Session-Id')).toBe('tk_old');
  });

  it('changePassword keeps a wrong current password (401 INVALID_CREDENTIALS) as ApiError', async () => {
    const { client } = setup(jsonResponse(401, { code: 'INVALID_CREDENTIALS', message: 'x' }));
    await expect(
      client.changePassword('tk', { currentPassword: 'a', newPassword: 'b' }),
    ).rejects.toMatchObject({ name: 'ApiError', code: 'INVALID_CREDENTIALS' });
  });

  it('changePassword maps 400 PASSWORD_POLICY to ApiError', async () => {
    const { client } = setup(jsonResponse(400, { code: 'PASSWORD_POLICY', message: 'too short' }));
    await expect(
      client.changePassword('tk', { currentPassword: 'a', newPassword: 'b' }),
    ).rejects.toMatchObject({ code: 'PASSWORD_POLICY', status: 400, message: 'too short' });
  });

  it('throws UnauthorizedError on a 401 from an authenticated endpoint', async () => {
    const { client } = setup(jsonResponse(401, { code: 'UNAUTHORIZED', message: 'x' }));
    await expect(client.listUsers('tk', { page: 0, size: 20 })).rejects.toBeInstanceOf(
      UnauthorizedError,
    );
  });

  it('throws UnauthorizedError on a 401 with a non-JSON body', async () => {
    const { client } = setup(new Response('<html>', { status: 401 }));
    await expect(client.me('tk')).rejects.toBeInstanceOf(UnauthorizedError);
  });

  it('throws PasswordChangeRequiredError on 403 PASSWORD_CHANGE_REQUIRED', async () => {
    const { client } = setup(jsonResponse(403, { code: 'PASSWORD_CHANGE_REQUIRED', message: 'x' }));
    await expect(client.listTenants('tk', { page: 0, size: 20 })).rejects.toBeInstanceOf(
      PasswordChangeRequiredError,
    );
  });

  it('throws ApiError with UNKNOWN code on an unexpected failure', async () => {
    const { client } = setup(new Response('boom', { status: 500 }));
    const error = await client.me('tk').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 500, code: 'UNKNOWN' });
  });

  it('uses the global fetch when none is injected', async () => {
    const original = globalThis.fetch;
    const spy = vi.fn(async () => jsonResponse(200, { items: [], page: 0, size: 20, total: 0 }));
    globalThis.fetch = spy as unknown as typeof fetch;
    try {
      const client = createTenantCoreClient({ baseUrl: 'http://api.test' });
      await client.listTenants('tk', { page: 0, size: 20 });
      expect(spy).toHaveBeenCalledOnce();
    } finally {
      globalThis.fetch = original;
    }
  });
});
