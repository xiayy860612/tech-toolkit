import Link from 'next/link';
import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';

export interface Column<T> {
  header: string;
  cell: (row: T) => ReactNode;
}

interface ResponsiveListProps<T> {
  caption: string;
  columns: Column<T>[];
  rows: T[];
  emptyText: string;
  /** Path used to build the pagination links (query: page, size). */
  basePath: string;
  page: number;
  size: number;
  total: number;
}

function PagerLink({ href, label, enabled }: { href: string; label: string; enabled: boolean }) {
  return enabled ? (
    <Button asChild variant="outline" className="h-11 px-4">
      <Link href={href}>{label}</Link>
    </Button>
  ) : (
    <span
      aria-disabled="true"
      className="inline-flex h-11 items-center px-4 text-sm text-muted-foreground"
    >
      {label}
    </span>
  );
}

/**
 * Read-only list: a Table from the `sm` breakpoint up, one Card per record below it.
 * Both variants are rendered; the breakpoint classes decide which one is displayed.
 */
export function ResponsiveList<T>({
  caption,
  columns,
  rows,
  emptyText,
  basePath,
  page,
  size,
  total,
}: ResponsiveListProps<T>) {
  if (rows.length === 0) {
    return <p className="text-muted-foreground">{emptyText}</p>;
  }

  const pageCount = Math.max(1, Math.ceil(total / size));
  const href = (target: number) => `${basePath}?page=${target}&size=${size}`;

  return (
    <>
      <div className="hidden sm:block">
        <Table aria-label={caption}>
          <TableHeader>
            <TableRow>
              {columns.map((column) => (
                <TableHead key={column.header} scope="col">
                  {column.header}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((row, index) => (
              <TableRow key={index}>
                {columns.map((column) => (
                  <TableCell key={column.header}>{column.cell(row)}</TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <ul aria-label={caption} className="flex flex-col gap-3 sm:hidden">
        {rows.map((row, index) => (
          <li key={index}>
            <Card className="gap-2 p-4">
              <dl className="flex flex-col gap-2">
                {columns.map((column) => (
                  <div key={column.header} className="flex items-start justify-between gap-4">
                    <dt className="shrink-0 text-sm text-muted-foreground">{column.header}</dt>
                    <dd className="min-w-0 text-right text-sm break-words">{column.cell(row)}</dd>
                  </div>
                ))}
              </dl>
            </Card>
          </li>
        ))}
      </ul>
      <nav aria-label="分页" className="mt-4 flex flex-wrap items-center justify-between gap-2">
        <PagerLink href={href(page - 1)} label="上一页" enabled={page > 0} />
        <span className="text-sm">{`第 ${page + 1} / ${pageCount} 页，共 ${total} 条`}</span>
        <PagerLink href={href(page + 1)} label="下一页" enabled={page + 1 < pageCount} />
      </nav>
    </>
  );
}
