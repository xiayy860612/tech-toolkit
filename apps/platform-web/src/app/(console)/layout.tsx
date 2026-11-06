import type { ReactNode } from 'react';
import { Sidebar } from '@/components/Sidebar';
import { requireSession } from '@/server/auth';

export default async function ConsoleLayout({ children }: { children: ReactNode }) {
  await requireSession();
  return (
    <div className="shell">
      <aside>
        <Sidebar />
        <form action="/logout" method="post">
          <button type="submit">退出登录</button>
        </form>
      </aside>
      <main>{children}</main>
    </div>
  );
}
