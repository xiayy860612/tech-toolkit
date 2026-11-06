import { DataTable, type Column } from '@/components/DataTable';
import { formatDateTime } from '@/lib/format';
import { parsePageQuery } from '@/lib/pagination';
import type { Tenant } from '@/lib/types';
import { runWithSession } from '@/server/auth';

const columns: Column<Tenant>[] = [
  { header: 'Tenant Slug', cell: (tenant) => tenant.slug },
  { header: '名称', cell: (tenant) => tenant.name },
  { header: '状态', cell: (tenant) => tenant.status },
  { header: '创建时间', cell: (tenant) => formatDateTime(tenant.createdAt) },
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
    <section>
      <h1>租户管理</h1>
      <DataTable
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
