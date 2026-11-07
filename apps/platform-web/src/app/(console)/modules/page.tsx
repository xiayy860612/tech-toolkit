import { LocalTime } from '@/components/LocalTime';
import { ResponsiveList, type Column } from '@/components/ResponsiveList';
import { StatusBadge } from '@/components/StatusBadge';
import { parsePageQuery } from '@/lib/pagination';
import type { ModuleSubscription } from '@/lib/types';
import { runWithSession } from '@/server/auth';

const columns: Column<ModuleSubscription>[] = [
  { header: '模块', cell: (subscription) => subscription.moduleCode },
  { header: '租户', cell: (subscription) => subscription.tenantSlug },
  { header: '订阅状态', cell: (subscription) => <StatusBadge status={subscription.status} /> },
  { header: '订阅时间', cell: (subscription) => <LocalTime iso={subscription.subscribedAt} /> },
];

export default async function ModulesPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const query = parsePageQuery(await searchParams);
  const result = await runWithSession((client, session) =>
    client.listModuleSubscriptions(session.token, query),
  );
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">模块管理</h1>
      <ResponsiveList
        caption="模块订阅列表"
        columns={columns}
        rows={result.items}
        emptyText="暂无模块订阅"
        basePath="/modules"
        page={result.page}
        size={result.size}
        total={result.total}
      />
    </section>
  );
}
