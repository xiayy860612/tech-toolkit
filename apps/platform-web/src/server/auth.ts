import { cookies, headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { safeNextPath } from '@/lib/safeNextPath';
import { getSessionStore, getTenantCoreClient } from './container';
import { SESSION_COOKIE } from './sessionCookie';
import {
  PasswordChangeRequiredError,
  UnauthorizedError,
  type TenantCoreClient,
} from './tenantCoreClient';

export interface ActiveSession {
  sessionId: string;
  /** Opaque Token: server-side only, never pass it to client components. */
  token: string;
  mustChangePassword: boolean;
}

async function currentPath(): Promise<string> {
  return safeNextPath((await headers()).get('x-next-path'));
}

function loginUrl(next: string): string {
  return `/login?next=${encodeURIComponent(next)}`;
}

/** The route handler there clears the cookie, then sends the User to /login (avoids a /login <-> page loop). */
function expiredUrl(next: string): string {
  return `/session/expired?next=${encodeURIComponent(next)}`;
}

/**
 * Real Session validation (the middleware only checks cookie presence, it cannot reach Redis).
 * Redirects: no cookie -> /login, unknown/expired Session -> /session/expired,
 * mustChangePassword -> /change-password (unless the caller is the change-password page).
 */
export async function requireSession(
  options: { allowMustChangePassword?: boolean } = {},
): Promise<ActiveSession> {
  const sessionId = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!sessionId) redirect(loginUrl(await currentPath()));

  const session = await getSessionStore().get(sessionId);
  if (!session) redirect(expiredUrl(await currentPath()));

  if (session.mustChangePassword && !options.allowMustChangePassword) {
    redirect('/change-password');
  }
  return { sessionId, token: session.token, mustChangePassword: session.mustChangePassword };
}

/** Runs a backend call for the current Session and applies the shared error policy. */
export async function runWithSession<T>(
  call: (client: TenantCoreClient, session: ActiveSession) => Promise<T>,
  options: { allowMustChangePassword?: boolean } = {},
): Promise<T> {
  const session = await requireSession(options);
  try {
    return await call(getTenantCoreClient(), session);
  } catch (error) {
    if (error instanceof UnauthorizedError) {
      await getSessionStore().destroy(session.sessionId);
      redirect(expiredUrl(await currentPath()));
    }
    if (error instanceof PasswordChangeRequiredError && !options.allowMustChangePassword) {
      redirect('/change-password');
    }
    throw error;
  }
}
