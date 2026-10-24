import { randomUUID } from "node:crypto";
import type { Metadata } from "next";
import { Breadcrumbs } from "@/components/shell/breadcrumbs";
import { UserForm } from "@/components/users/user-form";
import { requireOperator } from "@/lib/session/get-session";

export const metadata: Metadata = { title: "新建用户" };

/**
 * 新建用户（FR-021）。每次渲染生成一个新的 `Idempotency-Key`（contracts/web-routes.md §4）：
 * 同一张表单的双击与重试带同一个键，UAM 只建一个用户；重新打开页面得到新键。
 * 本页不读 UAM、没有数据，但仍须先调用 `requireOperator()`（US13）：匿名访问必须被跳转到登录页，
 * 不能让 Next.js 的局部渲染绕过外壳布局、把这张表单渲染给匿名访问者；unverified 时表单照常渲染
 * （不含任何数据，与 `account/password/page.tsx` 一致）。
 */
export default async function NewUserPage() {
  await requireOperator();
  return (
    <>
      <Breadcrumbs items={[{ label: "用户管理", href: "/users" }, { label: "新建用户" }]} />
      <div className="mb-4">
        <h1 className="text-2xl font-bold">新建用户</h1>
        <p className="text-muted-foreground">创建一个用户及其一个「账号名 / 密码」账号。</p>
      </div>
      <UserForm idempotencyKey={randomUUID()} />
    </>
  );
}
