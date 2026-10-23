import type { Metadata } from "next";
import { Breadcrumbs } from "@/components/shell/breadcrumbs";
import { UamReadError } from "@/components/users/uam-read-error";
import { UserDetail, UserNotFound } from "@/components/users/user-detail";
import { getOperator } from "@/lib/session/get-session";
import { getUser, type UserDetail as UserDetailData } from "@/lib/uam/client";
import { UamError } from "@/lib/uam/errors";
import { parseUserId } from "@/lib/validation/users-query";

export const metadata: Metadata = { title: "用户详情" };

const LIST = { label: "用户管理", href: "/users" };

/**
 * 用户详情（FR-019、FR-020）：基本信息与全部账号。
 * 状态管理（停用 / 启用）在详情页内；目标是当前登录用户时入口禁用（`isSelf`）。
 * 新建成功后以 `?created=1` 跳转而来，显示一次性提示：只认精确的 `1`，纯展示、不携带数据；选它而不是 cookie，因为最简单且无状态。
 * 不存在（UAM 404 `user_not_found`）或格式非法的 `userId` 显示「用户不存在」；其它读失败显示「服务暂不可用」。
 */
export default async function UserDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ userId: string }>;
  searchParams: Promise<{ created?: string | string[] }>;
}) {
  const userId = parseUserId((await params).userId);
  if (!userId) return notFound();

  let user: UserDetailData;
  try {
    user = await getUser(userId);
  } catch (error) {
    if (!(error instanceof UamError)) throw error;
    if (error.kind === "problem" && error.code === "user_not_found") return notFound();
    return (
      <>
        <Breadcrumbs items={[LIST, { label: "用户详情" }]} />
        <UamReadError error={error} retryHref={`/users/${userId}`} as="h1" />
      </>
    );
  }

  return (
    <>
      <Breadcrumbs items={[LIST, { label: user.name }]} />
      <UserDetail user={user} created={(await searchParams).created === "1"} isSelf={(await getOperator())?.userId === user.id} />
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
