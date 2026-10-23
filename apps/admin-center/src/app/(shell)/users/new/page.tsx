import { randomUUID } from "node:crypto";
import type { Metadata } from "next";
import { Breadcrumbs } from "@/components/shell/breadcrumbs";
import { UserForm } from "@/components/users/user-form";

export const metadata: Metadata = { title: "新建用户" };

/**
 * 新建用户（FR-021）。每次渲染生成一个新的 `Idempotency-Key`（contracts/web-routes.md §4）：
 * 同一张表单的双击与重试带同一个键，UAM 只建一个用户；重新打开页面得到新键。
 * 会话状态由外壳布局负责。
 */
export default async function NewUserPage() {
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
