import { Card } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Account } from "@/lib/uam/client";
import { FormattedTime } from "./formatted-time";
import { StateMessage } from "./state-card";
import { StatusBadge } from "./status-badge";

const KIND_LABELS: Record<Account["kind"], string> = { PASSWORD: "账号名 / 密码" };

/**
 * 用户的全部账号（FR-019）：账号名、账号种类、状态、创建时间。不含任何凭据。
 * 与用户列表同一套响应式做法：≥ 768 宽表格，< 768 宽卡片。
 */
export function AccountList({ accounts }: { accounts: Account[] }) {
  return (
    <Card className="gap-0 overflow-hidden py-0" role="region" aria-labelledby="accounts-heading">
      <h2 id="accounts-heading" className="px-4 pt-4 text-base font-semibold">{`关联账号（${accounts.length}）`}</h2>
      {accounts.length === 0 ? (
        <StateMessage title="该用户暂无关联账号" description="该用户目前无法登录。" />
      ) : (
        <>
          <div className="mt-2 hidden md:block">
            <Table aria-label="关联账号">
              <TableHeader>
                <TableRow>
                  <TableHead className="h-11 px-4">账号名</TableHead>
                  <TableHead className="h-11 px-4">账号种类</TableHead>
                  <TableHead className="h-11 px-4">状态</TableHead>
                  <TableHead className="h-11 px-4">创建时间</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {accounts.map((account) => (
                  <TableRow key={account.id}>
                    <TableCell className="max-w-64 px-4 py-3 font-medium break-all whitespace-normal">{account.loginName}</TableCell>
                    <TableCell className="px-4">{KIND_LABELS[account.kind]}</TableCell>
                    <TableCell className="px-4">
                      <StatusBadge status={account.status} />
                    </TableCell>
                    <TableCell className="px-4">
                      <FormattedTime iso={account.createdAt} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>

          <ul aria-label="关联账号卡片" className="mt-2 divide-y border-t md:hidden">
            {accounts.map((account) => (
              <li key={account.id} className="px-4 py-3">
                <p className="mb-1 font-semibold break-all">{account.loginName}</p>
                <dl className="grid grid-cols-[auto_1fr] items-center gap-x-4 gap-y-1 text-sm">
                  <dt className="text-muted-foreground">账号种类</dt>
                  <dd className="justify-self-end">{KIND_LABELS[account.kind]}</dd>
                  <dt className="text-muted-foreground">状态</dt>
                  <dd className="justify-self-end">
                    <StatusBadge status={account.status} />
                  </dd>
                  <dt className="text-muted-foreground">创建时间</dt>
                  <dd className="justify-self-end">
                    <FormattedTime iso={account.createdAt} />
                  </dd>
                </dl>
              </li>
            ))}
          </ul>
        </>
      )}
    </Card>
  );
}
