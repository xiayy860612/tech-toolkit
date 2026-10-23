import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { formatRelative } from "@/lib/format-date";
import type { UserSession } from "@/lib/uam/client";
import { ACCOUNT_KIND_LABELS } from "@/components/users/account-kind";
import { FormattedTime } from "@/components/users/formatted-time";
import { StateCard } from "@/components/users/state-card";

export interface SessionListProps {
  sessions: UserSession[];
  /** 计算相对时间的「当前时间」（毫秒）；缺省取 `Date.now()`，测试里传固定值。 */
  now?: number;
}

/** 会话引用（非机密）只显示前 8 位，足以区分同一用户的几个会话；机密的会话标识从不出现在页面上。 */
const shortRef = (ref: string) => ref.slice(0, 8);

/**
 * 某用户当前的全部有效会话（FR-029、FR-031）：每个会话一张卡片（各视口一致），含账号及其种类、
 * 登录 / 最近活动 / 到期时间与会话引用；运维人员自己的会话标注「当前会话」。强制下线入口属于后续用户故事。
 */
export function SessionList({ sessions, now = Date.now() }: SessionListProps) {
  if (sessions.length === 0) {
    return (
      <StateCard title="该用户当前没有在线会话" description="可能已退出、到期或被下线。">
        <Button asChild variant="outline" className="h-11">
          <Link href="/sessions">返回在线用户</Link>
        </Button>
      </StateCard>
    );
  }

  return (
    <ul aria-label="会话列表" className="grid gap-3">
      {sessions.map((session) => (
        <li key={session.sessionRef}>
          <Card className="gap-3 p-4">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
              <span className="font-bold break-all">{session.accountName}</span>
              <Badge variant="outline">{ACCOUNT_KIND_LABELS[session.accountKind]}</Badge>
              {session.current && <Badge variant="secondary">当前会话</Badge>}
            </div>
            <dl className="grid grid-cols-1 gap-x-6 gap-y-1 sm:grid-cols-[max-content_minmax(0,1fr)]">
              <dt className="text-muted-foreground">登录时间</dt>
              <dd className="mb-2 sm:mb-0">
                <FormattedTime iso={session.loginAt} />
              </dd>
              <dt className="text-muted-foreground">最近活动</dt>
              <dd className="mb-2 sm:mb-0">
                <FormattedTime iso={session.lastActiveAt} />
                <span className="text-muted-foreground">{`（${formatRelative(session.lastActiveAt, now)}）`}</span>
              </dd>
              <dt className="text-muted-foreground">到期时间</dt>
              <dd className="mb-2 sm:mb-0">
                <FormattedTime iso={session.expiresAt} />
              </dd>
              <dt className="text-muted-foreground">会话引用</dt>
              <dd>
                <code>{shortRef(session.sessionRef)}</code>
              </dd>
            </dl>
          </Card>
        </li>
      ))}
    </ul>
  );
}
