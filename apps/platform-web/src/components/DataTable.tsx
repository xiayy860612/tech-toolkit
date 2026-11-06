import Link from 'next/link';
import type { ReactNode } from 'react';

export interface Column<T> {
  header: string;
  cell: (row: T) => ReactNode;
}

interface DataTableProps<T> {
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
  return enabled ? <Link href={href}>{label}</Link> : <span aria-disabled="true">{label}</span>;
}

/** Read-only table: no row actions, no forms. */
export function DataTable<T>({
  caption,
  columns,
  rows,
  emptyText,
  basePath,
  page,
  size,
  total,
}: DataTableProps<T>) {
  if (rows.length === 0) {
    return <p className="empty">{emptyText}</p>;
  }

  const pageCount = Math.max(1, Math.ceil(total / size));
  const href = (target: number) => `${basePath}?page=${target}&size=${size}`;

  return (
    <>
      <table aria-label={caption}>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.header} scope="col">
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={index}>
              {columns.map((column) => (
                <td key={column.header}>{column.cell(row)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <nav aria-label="分页" className="pager">
        <PagerLink href={href(page - 1)} label="上一页" enabled={page > 0} />
        <span>{`第 ${page + 1} / ${pageCount} 页，共 ${total} 条`}</span>
        <PagerLink href={href(page + 1)} label="下一页" enabled={page + 1 < pageCount} />
      </nav>
    </>
  );
}
