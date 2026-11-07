import { LocalTime } from '@/components/LocalTime';
import { ResponsiveList, type Column } from '@/components/ResponsiveList';
import { StatusBadge } from '@/components/StatusBadge';
import { parsePageQuery } from '@/lib/pagination';
import type { Tenant } from '@/lib/types';
import { runWithSession } from '@/server/auth';

const columns: Column<Tenant>[] = [
  { header: '租户标识', cell: (tenant) => tenant.slug },
  { header: '名称', cell: (tenant) => tenant.name },
  { header: '状态', cell: (tenant) => <StatusBadge status={tenant.status} /> },
  { header: '创建时间', cell: (tenant) => <LocalTime iso={tenant.createdAt} /> },
];

export default async function TenantsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const query = parsePageQuery(await searchParams);
  const result = await runWithSession((client, session) =>
    client.listTenants(session.token, query),
  );
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">租户管理</h1>
      <ResponsiveList
        caption="租户列表"
        columns={columns}
        rows={result.items}
        emptyText="暂无租户"
        basePath="/tenants"
        page={result.page}
        size={result.size}
        total={result.total}
      />
    </section>
  );
}
