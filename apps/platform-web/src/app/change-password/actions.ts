'use server';

import { redirect } from 'next/navigation';
import { runWithSession } from '@/server/auth';
import { getSessionStore } from '@/server/container';
import { setSessionCookie } from '@/server/sessionCookie';
import {
  ApiError,
  PasswordChangeRequiredError,
  UnauthorizedError,
} from '@/server/tenantCoreClient';

export interface ChangePasswordState {
  error?: 'MISMATCH' | 'INVALID_CURRENT_PASSWORD' | 'PASSWORD_POLICY' | 'UNAVAILABLE';
  /** Backend explanation of the password policy violation. */
  detail?: string;
}

function text(data: FormData, name: string): string {
  const value = data.get(name);
  return typeof value === 'string' ? value : '';
}

function toState(error: unknown): ChangePasswordState {
  if (error instanceof ApiError && error.code === 'INVALID_CREDENTIALS') {
    return { error: 'INVALID_CURRENT_PASSWORD' };
  }
  if (error instanceof ApiError && error.code === 'PASSWORD_POLICY') {
    return { error: 'PASSWORD_POLICY', detail: error.message };
  }
  return { error: 'UNAVAILABLE' };
}

export async function changePasswordAction(
  _previous: ChangePasswordState,
  data: FormData,
): Promise<ChangePasswordState> {
  const currentPassword = text(data, 'currentPassword');
  const newPassword = text(data, 'newPassword');
  if (newPassword !== text(data, 'confirmPassword')) return { error: 'MISMATCH' };

  let outcome: ChangePasswordState | undefined;
  await runWithSession(
    async (client, session) => {
      try {
        const login = await client.changePassword(session.token, { currentPassword, newPassword });
        // The backend revoked the old Opaque Token: keep the Session id, swap in the new token.
        await getSessionStore().replace(session.sessionId, login);
        await setSessionCookie(session.sessionId, login.expiresAt);
      } catch (error) {
        // These two must reach runWithSession, which clears the Session / redirects.
        if (error instanceof UnauthorizedError || error instanceof PasswordChangeRequiredError) {
          throw error;
        }
        outcome = toState(error);
      }
    },
    { allowMustChangePassword: true },
  );

  if (outcome) return outcome;
  redirect('/tenants');
}
