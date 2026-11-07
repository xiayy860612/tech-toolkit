import type { ReactNode } from 'react';
import { UserMenu, type CurrentUser } from '@/components/UserMenu';

/** Global top bar: product name on the left, user menu on the right; `leading` is the menu button. */
export function TopBar({ user, leading }: { user: CurrentUser; leading?: ReactNode }) {
  return (
    <header className="sticky top-0 z-10 flex h-14 items-center gap-2 border-b bg-background px-2 md:px-4">
      {leading}
      <span className="text-base font-semibold">Platform</span>
      <div className="ml-auto">
        <UserMenu user={user} />
      </div>
    </header>
  );
}
