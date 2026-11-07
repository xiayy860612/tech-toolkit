import { LocalTime } from '@/components/LocalTime';
import { ResponsiveList, type Column } from '@/components/ResponsiveList';
import { StatusBadge } from '@/components/StatusBadge';
import { formatUsernames } from '@/lib/format';
import { parsePageQuery } from '@/lib/pagination';
import type { User } from '@/lib/types';
import { runWithSession } from '@/server/auth';

const columns: Column<User>[] = [
  { header: '显示名', cell: (user) => user.displayName },
  // A User without an Account has no usernames: shown as a dash.
  { header: '用户名', cell: (user) => formatUsernames(user.accountUsernames) },
  { header: '租户', cell: (user) => user.tenantSlug },
  { header: '状态', cell: (user) => <StatusBadge status={user.status} /> },
  { header: '创建时间', cell: (user) => <LocalTime iso={user.createdAt} /> },
];

export default async function UsersPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const query = parsePageQuery(await searchParams);
  const result = await runWithSession((client, session) => client.listUsers(session.token, query));
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">用户管理</h1>
      <ResponsiveList
        caption="用户列表"
        columns={columns}
        rows={result.items}
        emptyText="暂无用户"
        basePath="/users"
        page={result.page}
        size={result.size}
        total={result.total}
      />
    </section>
  );
}
