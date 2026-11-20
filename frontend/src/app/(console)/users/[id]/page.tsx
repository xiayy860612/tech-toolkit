import { headers } from "next/headers";
import { UserDetail } from "@/features/users/UserDetail";
import { getCurrentSession } from "@/server/session/current";

/** 用户详情：用户信息 + 登录账号区。会话管理是独立页面 /sessions，不在此处。 */
export default async function UserDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const me = await getCurrentSession(await headers());
  return <UserDetail id={id} currentUserId={me?.userId} />;
}
