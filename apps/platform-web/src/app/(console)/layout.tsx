import type { ReactNode } from 'react';
import { AppShell } from '@/components/AppShell';
import { runWithSession } from '@/server/auth';

export default async function ConsoleLayout({ children }: { children: ReactNode }) {
  // runWithSession enforces the Session (and the 401 policy) before the caller is loaded.
  const me = await runWithSession((client, session) => client.me(session.token));
  const user = {
    displayName: me.displayName,
    accountUsername: me.accountUsername,
    tenantSlug: me.tenantSlug,
  };
  return <AppShell user={user}>{children}</AppShell>;
}
