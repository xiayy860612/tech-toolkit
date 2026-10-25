import type { Metadata } from "next";
import { ChangePasswordForm } from "@/components/auth/change-password-form";
import { Breadcrumbs } from "@/components/shell/breadcrumbs";
import { getSession, requireOperator } from "@/lib/session/get-session";

export const metadata: Metadata = { title: "修改密码" };

/**
 * 修改自己的密码（FR-026）。被改的账号永远是当前登录所用的账号（由 Server Action 在服务端取自会话，页面不传任何账号标识）。
 * 本页不读 UAM、没有数据，但仍须先调用 `requireOperator()`（US13）：匿名访问必须被跳转到登录页，
 * 不能让 Next.js 的局部渲染绕过外壳布局、把这张表单渲染给匿名访问者；unverified 时表单照常渲染，
 * 只是不点名账号（外壳已提示服务暂不可用）。
 */
export default async function ChangePasswordPage() {
  await requireOperator();
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
