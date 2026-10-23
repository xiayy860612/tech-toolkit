import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { UserSummary } from "@/lib/uam/client";
import { usersHref } from "@/lib/users-href";
import { FormattedTime } from "./formatted-time";
import { Pagination } from "./pagination";
import { StateCard } from "./state-card";
import { StatusBadge } from "./status-badge";

export interface UserListProps {
  users: UserSummary[];
  total: number;
  page: number;
  size: number;
  /** 当前搜索词（已清洗）；空串表示没有搜索。 */
  query: string;
}

/**
 * 用户列表：≥ 768 宽是表格，< 768 宽是堆叠的卡片（FR-035）。两份标记同时输出、用 CSS 切换：
 * 表格在手机上不会被压成块级元素（保住表格语义），卡片是真正的列表。
 */
export function UserList({ users, total, page, size, query }: UserListProps) {
  if (users.length === 0) {
    return query ? (
      <StateCard title={`没有找到匹配「${query}」的用户`} description="换个关键词试试。">
        <Button asChild variant="outline" className="h-11">
          <Link href="/users">清除搜索</Link>
        </Button>
      </StateCard>
    ) : (
      <StateCard title="还没有用户" description="系统中暂时没有任何用户。" />
    );
  }

  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <Card className="gap-0 overflow-hidden py-0">
      <div className="hidden md:block">
        <Table aria-label="用户列表">
          <TableHeader>
            <TableRow>
              <TableHead className="h-11 px-4">用户名</TableHead>
              <TableHead className="h-11 px-4">状态</TableHead>
              <TableHead className="h-11 px-4">关联账号数</TableHead>
              <TableHead className="h-11 px-4">创建时间</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {users.map((user) => (
              <TableRow key={user.id}>
                <TableCell className="max-w-64 px-4 break-all whitespace-normal">
                  <Link
                    href={`/users/${user.id}`}
                    className="inline-flex min-h-11 items-center font-medium text-primary underline-offset-4 hover:underline"
                  >
                    {user.name}
                  </Link>
                </TableCell>
                <TableCell className="px-4">
                  <StatusBadge status={user.status} />
                </TableCell>
                <TableCell className="px-4">{user.accountCount}</TableCell>
                <TableCell className="px-4">
                  <FormattedTime iso={user.createdAt} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <ul aria-label="用户卡片" className="divide-y md:hidden">
        {users.map((user) => (
          <li key={user.id} className="relative px-4 py-3 hover:bg-muted/50">
            <Link
              href={`/users/${user.id}`}
              className="inline-flex min-h-11 items-center font-semibold break-all text-primary after:absolute after:inset-0 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring"
            >
              {user.name}
            </Link>
            <dl className="grid grid-cols-[auto_1fr] items-center gap-x-4 gap-y-1 text-sm">
              <dt className="text-muted-foreground">状态</dt>
              <dd className="justify-self-end">
                <StatusBadge status={user.status} />
              </dd>
              <dt className="text-muted-foreground">关联账号数</dt>
              <dd className="justify-self-end">{user.accountCount}</dd>
              <dt className="text-muted-foreground">创建时间</dt>
              <dd className="justify-self-end">
                <FormattedTime iso={user.createdAt} />
              </dd>
            </dl>
          </li>
        ))}
      </ul>

      <Pagination
        summary={`共 ${total} 位用户 · 第 ${page} / ${pages} 页`}
        prevHref={page > 1 ? usersHref(query, page - 1) : null}
        nextHref={page < pages ? usersHref(query, page + 1) : null}
      />
    </Card>
  );
}
