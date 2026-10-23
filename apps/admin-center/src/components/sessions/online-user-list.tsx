import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatDateTime, formatRelative } from "@/lib/format-date";
import { sessionsHref } from "@/lib/sessions-href";
import type { OnlineUser } from "@/lib/uam/client";
import { Pagination } from "@/components/users/pagination";
import { StateCard } from "@/components/users/state-card";

export interface OnlineUserListProps {
  users: OnlineUser[];
  total: number;
  page: number;
  size: number;
  /** 运维人员自己所在的用户；该行带「你」标注。 */
  currentUserId?: string;
  /** 计算相对时间的「当前时间」（毫秒）；缺省取 `Date.now()`，测试里传固定值。 */
  now?: number;
}

/** 最近活动：可见文本是相对时间，绝对时间在 `title`，机器可读值在 `dateTime`。 */
function LastActive({ iso, now }: { iso: string; now: number }) {
  return (
    <time dateTime={iso} title={formatDateTime(iso)}>
      {formatRelative(iso, now)}
    </time>
  );
}

function SelfBadge() {
  return (
    <Badge variant="secondary" className="ml-2">
      你
    </Badge>
  );
}

/**
 * 在线用户列表（FR-028）：用户名、有效会话数、最近活动。≥ 768 宽是表格，< 768 宽是堆叠的卡片（FR-035），
 * 与用户列表同一套做法。点用户名进入该用户的会话详情。
 */
export function OnlineUserList({ users, total, page, size, currentUserId, now = Date.now() }: OnlineUserListProps) {
  if (users.length === 0) {
    return <StateCard title="当前没有在线用户" description="有用户登录后会显示在这里。" />;
  }

  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <Card className="gap-0 overflow-hidden py-0">
      <div className="hidden md:block">
        <Table aria-label="在线用户列表">
          <TableHeader>
            <TableRow>
              <TableHead className="h-11 px-4">用户名</TableHead>
              <TableHead className="h-11 px-4">有效会话数</TableHead>
              <TableHead className="h-11 px-4">最近活动</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {users.map((user) => (
              <TableRow key={user.userId}>
                <TableCell className="max-w-64 px-4 break-all whitespace-normal">
                  <Link
                    href={`/sessions/${user.userId}`}
                    className="inline-flex min-h-11 items-center font-medium text-primary underline-offset-4 hover:underline"
                  >
                    {user.userName}
                  </Link>
                  {user.userId === currentUserId && <SelfBadge />}
                </TableCell>
                <TableCell className="px-4">{user.sessionCount}</TableCell>
                <TableCell className="px-4">
                  <LastActive iso={user.lastActiveAt} now={now} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <ul aria-label="在线用户卡片" className="divide-y md:hidden">
        {users.map((user) => (
          <li key={user.userId} className="relative px-4 py-3 hover:bg-muted/50">
            <span className="flex flex-wrap items-center">
              <Link
                href={`/sessions/${user.userId}`}
                className="inline-flex min-h-11 items-center font-semibold break-all text-primary after:absolute after:inset-0 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring"
              >
                {user.userName}
              </Link>
              {user.userId === currentUserId && <SelfBadge />}
            </span>
            <dl className="grid grid-cols-[auto_1fr] items-center gap-x-4 gap-y-1 text-sm">
              <dt className="text-muted-foreground">有效会话数</dt>
              <dd className="justify-self-end">{user.sessionCount}</dd>
              <dt className="text-muted-foreground">最近活动</dt>
              <dd className="justify-self-end">
                <LastActive iso={user.lastActiveAt} now={now} />
              </dd>
            </dl>
          </li>
        ))}
      </ul>

      <Pagination
        summary={`共 ${total} 位在线用户 · 第 ${page} / ${pages} 页`}
        prevHref={page > 1 ? sessionsHref(page - 1) : null}
        nextHref={page < pages ? sessionsHref(page + 1) : null}
      />
    </Card>
  );
}
