import { DataTable, type Column } from '@/components/DataTable';
import { formatDateTime } from '@/lib/format';
import { parsePageQuery } from '@/lib/pagination';
import type { User } from '@/lib/types';
import { runWithSession } from '@/server/auth';

const columns: Column<User>[] = [
  { header: 'Display Name', cell: (user) => user.displayName },
  // A User without an Account has no usernames: the cell stays empty.
  { header: 'Username', cell: (user) => user.accountUsernames.join(', ') },
  { header: 'Tenant Slug', cell: (user) => user.tenantSlug },
  { header: '状态', cell: (user) => user.status },
  { header: '创建时间', cell: (user) => formatDateTime(user.createdAt) },
];

export default async function UsersPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const query = parsePageQuery(await searchParams);
  const result = await runWithSession((client, session) => client.listUsers(session.token, query));
  return (
    <section>
      <h1>用户管理</h1>
      <DataTable
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
