// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest';

const jar = vi.hoisted(() => ({ set: vi.fn(), delete: vi.fn() }));
vi.mock('next/headers', () => ({ cookies: async () => jar }));

import {
  SESSION_COOKIE,
  clearSessionCookie,
  sessionCookieOptions,
  setSessionCookie,
} from '@/server/sessionCookie';

describe('session cookie', () => {
  beforeEach(() => {
    jar.set.mockClear();
    jar.delete.mockClear();
  });

  it('is HttpOnly, SameSite=Lax and scoped to the whole site', () => {
    expect(sessionCookieOptions(1800, true)).toEqual({
      httpOnly: true,
      secure: true,
      sameSite: 'lax',
      path: '/',
      maxAge: 1800,
    });
  });

  it('can be non-Secure for plain-http local development', () => {
    expect(sessionCookieOptions(60, false).secure).toBe(false);
  });

  it('setSessionCookie stores the Session id with a max age aligned to the token expiry', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(Date.parse('2030-01-01T00:00:00Z'));
    try {
      await setSessionCookie('sid123', '2030-01-01T00:30:00Z');
    } finally {
      vi.useRealTimers();
    }
    expect(jar.set).toHaveBeenCalledWith(
      SESSION_COOKIE,
      'sid123',
      expect.objectContaining({ httpOnly: true, sameSite: 'lax', maxAge: 1800 }),
    );
  });

  it('clearSessionCookie deletes the cookie', async () => {
    await clearSessionCookie();
    expect(jar.delete).toHaveBeenCalledWith(SESSION_COOKIE);
  });
});
