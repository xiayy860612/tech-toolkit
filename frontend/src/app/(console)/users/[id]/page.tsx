import { AccountsSection } from "@/features/accounts/AccountsSection";
import { UserDetail } from "@/features/users/UserDetail";

/** 用户详情：用户信息 + Account 管理。会话管理是独立页面 /sessions，不在此处。 */
export default async function UserDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <div className="flex flex-col gap-5">
      <UserDetail id={id} />
      <AccountsSection userId={id} />
    </div>
  );
}
