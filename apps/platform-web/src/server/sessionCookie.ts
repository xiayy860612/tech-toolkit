import { cookies } from 'next/headers';
import { getEnv } from './env';
import { ttlSecondsUntil } from './session';

export const SESSION_COOKIE = 'platform_sid';

export function sessionCookieOptions(maxAge: number, secure: boolean) {
  return { httpOnly: true, secure, sameSite: 'lax' as const, path: '/', maxAge };
}

/** Writes the cookie carrying only the random Session id (never the Opaque Token). */
export async function setSessionCookie(sessionId: string, expiresAt: string): Promise<void> {
  const jar = await cookies();
  jar.set(
    SESSION_COOKIE,
    sessionId,
    sessionCookieOptions(ttlSecondsUntil(expiresAt), getEnv().cookieSecure),
  );
}

export async function clearSessionCookie(): Promise<void> {
  const jar = await cookies();
  jar.delete(SESSION_COOKIE);
}
