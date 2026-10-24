import type { Metadata } from "next";
import { SessionList } from "@/components/sessions/session-list";
import { TerminateFeedback } from "@/components/sessions/terminate-dialog";
import { Breadcrumbs } from "@/components/shell/breadcrumbs";
import { UamReadError } from "@/components/users/uam-read-error";
import { UserNotFound } from "@/components/users/user-detail";
import { requireOperator } from "@/lib/session/get-session";
import { getUser, listUserSessions, type UserDetail, type UserSession } from "@/lib/uam/client";
import { UamError } from "@/lib/uam/errors";
import { parseUserId } from "@/lib/validation/users-query";

export const metadata: Metadata = { title: "用户会话" };

const LIST = { label: "会话管理", href: "/sessions" };

/**
 * 某用户的当前会话（FR-029）。会话接口不返回用户名，所以并行取用户详情作页头；
 * 任一接口 404 `user_not_found` 或 `userId` 格式非法都显示「用户不存在」（不请求 UAM）；其它读失败显示「服务暂不可用」。
 * 操作人取自当前会话，UAM 据其会话引用标注「当前会话」。
 * `requireOperator()` 必须是本页第一步（US13，理由见 `users/page.tsx`）：unverified 时返回 `null`，
 * 本页不渲染任何内容、不请求 UAM（外壳布局负责显示「服务暂不可用」）。
 */
export default async function UserSessionsPage({ params }: { params: Promise<{ userId: string }> }) {
  const operator = await requireOperator();
  if (!operator) return null;

  const userId = parseUserId((await params).userId);
  if (!userId) return notFound();

  let user: UserDetail;
  let sessions: UserSession[];
  try {
    [user, sessions] = await Promise.all([getUser(userId, operator), listUserSessions(userId, operator)]);
  } catch (error) {
    if (!(error instanceof UamError)) throw error;
    if (error.kind === "problem" && error.code === "user_not_found") return notFound();
    return (
      <>
        <Breadcrumbs items={[LIST, { label: "会话" }]} />
        <UamReadError error={error} retryHref={`/sessions/${userId}`} as="h1" />
      </>
    );
  }

  return (
    <>
      <Breadcrumbs items={[LIST, { label: user.name }]} />
      <div className="mb-4">
        <h1 className="text-2xl font-bold break-all">{`${user.name} 的会话`}</h1>
        <p className="text-muted-foreground">每个会话对应该用户的一个账号。</p>
      </div>
      <TerminateFeedback>
        <SessionList userId={userId} sessions={sessions} />
      </TerminateFeedback>
    </>
  );
}

function notFound() {
  return (
    <>
      <Breadcrumbs items={[LIST, { label: "用户不存在" }]} />
      <UserNotFound />
    </>
  );
}
