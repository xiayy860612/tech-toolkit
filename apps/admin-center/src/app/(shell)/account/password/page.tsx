import type { Metadata } from "next";
import { ChangePasswordForm } from "@/components/auth/change-password-form";
import { Breadcrumbs } from "@/components/shell/breadcrumbs";
import { getSession } from "@/lib/session/get-session";

export const metadata: Metadata = { title: "修改密码" };

/**
 * 修改自己的密码（FR-026）。被改的账号永远是当前登录所用的账号（由 Server Action 在服务端取自会话，页面不传任何账号标识）。
 * 会话状态由外壳布局负责；这里只读它来点名账号（无法确认会话时不点名，外壳已提示服务暂不可用）。
 */
export default async function ChangePasswordPage() {
  const state = await getSession();
  const loginName = state.status === "authenticated" ? state.session.account.loginName : undefined;

  return (
    <>
      <Breadcrumbs items={[{ label: "修改密码" }]} />
      <div className="mb-4">
        <h1 className="text-2xl font-bold">修改密码</h1>
        <p className="text-muted-foreground">
          {loginName ? (
            <>
              修改当前登录账号 <code className="break-all">{loginName}</code> 的密码。修改后当前会话保持有效。
            </>
          ) : (
            "修改当前登录账号的密码。修改后当前会话保持有效。"
          )}
        </p>
      </div>
      <ChangePasswordForm />
    </>
  );
}
