'use server';

import { redirect } from 'next/navigation';
import { safeNextPath } from '@/lib/safeNextPath';
import { getSessionStore, getTenantCoreClient } from '@/server/container';
import { setSessionCookie } from '@/server/sessionCookie';
import { ApiError } from '@/server/tenantCoreClient';

export interface LoginState {
  error?: 'INVALID_CREDENTIALS' | 'MODULE_NOT_SUBSCRIBED' | 'UNAVAILABLE';
  /** Echoed back so the form can refill them (never the password). */
  tenantSlug?: string;
  username?: string;
}

function text(data: FormData, name: string): string {
  const value = data.get(name);
  return typeof value === 'string' ? value : '';
}

export async function loginAction(_previous: LoginState, data: FormData): Promise<LoginState> {
  const tenantSlug = text(data, 'tenantSlug').trim();
  const username = text(data, 'username').trim();
  const password = text(data, 'password');
  const echo = { tenantSlug, username };

  if (!tenantSlug || !username || !password) return { error: 'INVALID_CREDENTIALS', ...echo };

  let login;
  try {
    login = await getTenantCoreClient().login({ tenantSlug, username, password });
  } catch (error) {
    if (error instanceof ApiError && error.code === 'INVALID_CREDENTIALS') {
      return { error: 'INVALID_CREDENTIALS', ...echo };
    }
    if (error instanceof ApiError && error.code === 'MODULE_NOT_SUBSCRIBED') {
      return { error: 'MODULE_NOT_SUBSCRIBED', ...echo };
    }
    return { error: 'UNAVAILABLE', ...echo };
  }

  const sessionId = await getSessionStore().create(login);
  await setSessionCookie(sessionId, login.expiresAt);
  redirect(login.mustChangePassword ? '/change-password' : safeNextPath(text(data, 'next')));
}
