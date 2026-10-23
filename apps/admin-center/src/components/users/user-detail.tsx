import Link from "next/link";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import type { UserDetail as UserDetailData } from "@/lib/uam/client";
import { AccountList } from "./account-list";
import { FormattedTime } from "./formatted-time";
import { StateCard } from "./state-card";
import { StatusBadge } from "./status-badge";

/** 用户详情：基本信息 + 全部账号。停用 / 启用入口属于后续用户故事，这里没有。 */
export function UserDetail({ user }: { user: UserDetailData }) {
  return (
    <div className="grid gap-4">
      <header>
        <h1 className="mb-1 text-2xl font-bold break-all">{user.name}</h1>
        <StatusBadge status={user.status} />
      </header>

      {user.status === "DISABLED" && (
        <Alert role="status" className="border-0 bg-warning text-warning-foreground">
          <span className="col-start-2">该用户已停用：其名下所有账号都无法登录，也没有在线会话。</span>
        </Alert>
      )}

      <Card className="gap-3 p-4" role="region" aria-labelledby="basic-info-heading">
        <h2 id="basic-info-heading" className="text-base font-semibold">
          基本信息
        </h2>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-1 sm:grid-cols-[max-content_minmax(0,1fr)]">
          <dt className="text-muted-foreground">用户名</dt>
          <dd className="mb-2 break-all sm:mb-0">{user.name}</dd>
          <dt className="text-muted-foreground">状态</dt>
          <dd className="mb-2 sm:mb-0">
            <StatusBadge status={user.status} />
          </dd>
          <dt className="text-muted-foreground">创建时间</dt>
          <dd className="mb-2 sm:mb-0">
            <FormattedTime iso={user.createdAt} />
          </dd>
          <dt className="text-muted-foreground">用户标识</dt>
          <dd>
            <code>{user.id.slice(0, 8)}</code>
          </dd>
        </dl>
      </Card>

      <AccountList accounts={user.accounts} />
    </div>
  );
}

/** 不存在（或格式非法）的用户标识（FR-020）。 */
export function UserNotFound() {
  return (
    <StateCard title="用户不存在" description="该用户可能已被移除，或链接有误。" as="h1">
      <Button asChild variant="outline" className="h-11">
        <Link href="/users">返回用户列表</Link>
      </Button>
    </StateCard>
  );
}
