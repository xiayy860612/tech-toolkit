import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { OnlineUserList } from "@/components/sessions/online-user-list";
import { Breadcrumbs } from "@/components/shell/breadcrumbs";
import { UamReadError } from "@/components/users/uam-read-error";
import { requireOperator } from "@/lib/session/get-session";
import { sessionsHref } from "@/lib/sessions-href";
import { listOnlineUsers, type OnlineUserPage } from "@/lib/uam/client";
import { UamError } from "@/lib/uam/errors";
import { parsePage } from "@/lib/validation/users-query";

export const metadata: Metadata = { title: "会话管理" };

const CRUMBS = [{ label: "会话管理" }];

/**
 * Session Manager 在线用户列表（FR-028、FR-032）：`?page=` 分页，非法页码回到第 1 页。
 * 每次请求都向 UAM 取最新数据，已结束的会话刷新后即消失；UAM 不可用时显示「服务暂不可用」。
 * `requireOperator()` 必须是本页第一步（US13，理由见 `users/page.tsx`）；同一个操作人既用于
 * 调 `listOnlineUsers`，也用于标注「你」所在的那一行。
 */
export default async function SessionsPage({ searchParams }: { searchParams: Promise<{ page?: string | string[] }> }) {
  const operator = await requireOperator();
  if (!operator) return null; // unverified：外壳显示「服务暂不可用」，本页不渲染任何数据、不调用 UAM

  const page = parsePage((await searchParams).page);

  let data: OnlineUserPage;
  try {
    data = await listOnlineUsers({ page }, operator);
  } catch (error) {
    if (!(error instanceof UamError)) throw error;
    return (
      <>
        <Breadcrumbs items={CRUMBS} />
        <Header />
        <UamReadError error={error} retryHref={sessionsHref(page)} />
      </>
    );
  }

  // 页码越界（例如有用户下线后）：带到最后一页，而不是显示一个空页
  const lastPage = Math.max(1, Math.ceil(data.total / data.size));
  if (data.items.length === 0 && data.total > 0 && data.page > lastPage) redirect(sessionsHref(lastPage));

  return (
    <>
      <Breadcrumbs items={CRUMBS} />
      <Header />
      <OnlineUserList users={data.items} total={data.total} page={data.page} size={data.size} currentUserId={operator.userId} />
    </>
  );
}

function Header() {
  return (
    <div className="mb-4">
      <h1 className="text-2xl font-bold">会话管理</h1>
      <p className="text-muted-foreground">当前登录的所有用户（至少有一个有效会话）</p>
    </div>
  );
}
