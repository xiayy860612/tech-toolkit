import type { ReactNode } from 'react';
import { AppShell } from '@/components/AppShell';
import { requireSession } from '@/server/auth';

export default async function ConsoleLayout({ children }: { children: ReactNode }) {
  await requireSession();
  return <AppShell>{children}</AppShell>;
}
