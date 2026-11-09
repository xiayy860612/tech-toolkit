// @vitest-environment node
import { NextRequest } from 'next/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({
  cookie: undefined as string | undefined,
  store: { get: vi.fn(), destroy: vi.fn() },
  client: { logout: vi.fn() },
}));

vi.mock('@/server/container', () => ({
  getSessionStore: () => state.store,
  getTenantCoreClient: () => state.client,
}));

import { GET as healthz } from '@/app/healthz/route';
import { GET as expired } from '@/app/session/expired/route';
import { POST as logout } from '@/app/logout/route';

function request(path: string, method = 'GET'): NextRequest {
  const headers = new Headers();
  if (state.cookie) headers.set('cookie', `platform_sid=${state.cookie}`);
  return new NextRequest(`http://localhost:3000${path}`, { method, headers });
}

function setCookieHeader(response: Response): string {
  return response.headers.get('set-cookie') ?? '';
}

beforeEach(() => {
  state.cookie = 'sid123';
  state.store.get.mockReset();
  state.store.destroy.mockReset();
  state.client.logout.mockReset();
});

describe('POST /logout', () => {
  it('revokes the Opaque Token, destroys the Session and clears the cookie', async () => {
    state.store.get.mockResolvedValue({ token: 'tk_abc', mustChangePassword: false });

    const response = await logout(request('/logout', 'POST'));

    expect(state.client.logout).toHaveBeenCalledWith('tk_abc');
    expect(state.store.destroy).toHaveBeenCalledWith('sid123');
    expect(response.status).toBe(303);
    expect(response.headers.get('location')).toBe('/login');
    expect(setCookieHeader(response)).toMatch(/platform_sid=;/);
  });

  it('still clears local state when the backend revocation fails', async () => {
    state.store.get.mockResolvedValue({ token: 'tk_abc', mustChangePassword: false });
    state.client.logout.mockRejectedValue(new Error('down'));

    const response = await logout(request('/logout', 'POST'));

    expect(state.store.destroy).toHaveBeenCalledWith('sid123');
    expect(response.status).toBe(303);
  });

  it('works without a Session', async () => {
    state.cookie = undefined;
    const response = await logout(request('/logout', 'POST'));
    expect(state.client.logout).not.toHaveBeenCalled();
    expect(response.headers.get('location')).toBe('/login');
  });

  it('skips the backend call when the Session is already gone', async () => {
    state.store.get.mockResolvedValue(null);
    await logout(request('/logout', 'POST'));
    expect(state.client.logout).not.toHaveBeenCalled();
    expect(state.store.destroy).toHaveBeenCalledWith('sid123');
  });
});

describe('GET /session/expired', () => {
  it('destroys the Session, clears the cookie and redirects to /login?next=', async () => {
    const response = await expired(request('/session/expired?next=%2Fusers%3Fpage%3D2'));

    expect(state.store.destroy).toHaveBeenCalledWith('sid123');
    expect(response.status).toBe(303);
    expect(response.headers.get('location')).toBe('/login?next=%2Fusers%3Fpage%3D2');
    expect(setCookieHeader(response)).toMatch(/platform_sid=;/);
  });

  it('ignores an unsafe next', async () => {
    const response = await expired(request('/session/expired?next=https%3A%2F%2Fevil.example'));
    expect(response.headers.get('location')).toBe('/login');
  });

  it('works without a cookie', async () => {
    state.cookie = undefined;
    const response = await expired(request('/session/expired'));
    expect(state.store.destroy).not.toHaveBeenCalled();
    expect(response.headers.get('location')).toBe('/login');
  });
});

describe('GET /healthz', () => {
  it('answers ok', async () => {
    const response = await healthz();
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({ status: 'ok' });
  });
});
