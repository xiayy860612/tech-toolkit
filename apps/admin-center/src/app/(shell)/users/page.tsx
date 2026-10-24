import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Breadcrumbs } from "@/components/shell/breadcrumbs";
import { UamReadError } from "@/components/users/uam-read-error";
import { UserList } from "@/components/users/user-list";
import { UserSearchBox } from "@/components/users/user-search-box";
import { requireOperator } from "@/lib/session/get-session";
import { listUsers, type UserPage } from "@/lib/uam/client";
import { UamError } from "@/lib/uam/errors";
import { usersHref } from "@/lib/users-href";
import { parseUsersQuery } from "@/lib/validation/users-query";

export const metadata: Metadata = { title: "用户管理" };

type Param = string | string[] | undefined;

/**
 * User Manager 列表（FR-017、FR-018）：`?q=` 搜索、`?page=` 分页，非法参数回到默认值。
 * 数据来自 UAM；UAM 不可用时显示「服务暂不可用」（这是读 UAM 失败，不是会话问题）。
 * `requireOperator()` 必须是本页第一步（US13）：Next.js 的局部渲染可能跳过外壳布局的会话校验，
 * 只渲染这个页面段；不先确认操作人就绝不能调用 `listUsers`。
 */
export default async function UsersPage({ searchParams }: { searchParams: Promise<{ q?: Param; page?: Param }> }) {
  const operator = await requireOperator();
  if (!operator) return null; // unverified：外壳显示「服务暂不可用」，本页不渲染任何数据、不调用 UAM

  const { query, page } = parseUsersQuery(await searchParams);

  let data: UserPage;
  try {
    data = await listUsers({ query, page }, operator);
  } catch (error) {
    if (!(error instanceof UamError)) throw error;
    return (
      <>
        <Breadcrumbs items={[{ label: "用户管理" }]} />
        <Header />
        <UamReadError error={error} retryHref={usersHref(query, page)} />
      </>
    );
  }

  // 页码越界（例如搜索结果变少）：带到最后一页，而不是显示一个空页
  const lastPage = Math.max(1, Math.ceil(data.total / data.size));
  if (data.items.length === 0 && data.total > 0 && data.page > lastPage) redirect(usersHref(query, lastPage));

  return (
    <>
      <Breadcrumbs items={[{ label: "用户管理" }]} />
      <Header />
      <UserSearchBox defaultValue={query} />
      <UserList users={data.items} total={data.total} page={data.page} size={data.size} query={query} />
    </>
  );
}

function Header() {
  return (
    <div className="mb-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
      <div>
        <h1 className="text-2xl font-bold">用户管理</h1>
        <p className="text-muted-foreground">所有用户及其关联账号</p>
      </div>
      <Button asChild className="h-11 px-5">
        <Link href="/users/new">新建用户</Link>
      </Button>
    </div>
  );
}
