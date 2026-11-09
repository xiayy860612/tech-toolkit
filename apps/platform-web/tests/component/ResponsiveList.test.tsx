import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ResponsiveList, type Column } from '@/components/ResponsiveList';
import { StatusBadge } from '@/components/StatusBadge';

interface Row {
  name: string;
  status: string;
}

const columns: Column<Row>[] = [
  { header: '名称', cell: (row) => row.name },
  { header: '状态', cell: (row) => <StatusBadge status={row.status} /> },
];

function renderList(
  rows: Row[],
  pagination: { page: number; size: number; total: number } = {
    page: 0,
    size: 2,
    total: rows.length,
  },
) {
  return render(
    <ResponsiveList
      caption="租户列表"
      columns={columns}
      rows={rows}
      emptyText="暂无租户"
      basePath="/tenants"
      {...pagination}
    />,
  );
}

const rows: Row[] = [
  { name: 'acme', status: 'ACTIVE' },
  { name: 'globex', status: 'SUSPENDED' },
];

describe('ResponsiveList', () => {
  it('renders a Table (sm and up) with Chinese headers and rows', () => {
    renderList(rows);
    const table = screen.getByRole('table', { name: '租户列表' });
    expect(table.closest('.hidden')).toHaveClass('hidden', 'sm:block');
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['名称', '状态']);
    expect(screen.getByRole('cell', { name: 'acme' })).toBeInTheDocument();
  });

  it('renders cards (below sm) with a field name and value per line', () => {
    renderList(rows);
    const list = screen.getByRole('list', { name: '租户列表' });
    expect(list).toHaveClass('sm:hidden');
    const cards = within(list).getAllByRole('listitem');
    expect(cards).toHaveLength(2);
    const first = within(cards[0]);
    expect(first.getByText('名称').tagName).toBe('DT');
    expect(first.getByText('acme').tagName).toBe('DD');
    expect(first.getByText('状态')).toBeInTheDocument();
    expect(first.getByText('正常')).toBeInTheDocument();
  });

  it('renders status labels as Badges', () => {
    renderList(rows);
    const badge = within(screen.getByRole('table')).getByText('已暂停');
    expect(badge).toHaveAttribute('data-slot', 'badge');
  });

  it('shows only the empty-state text when there are no rows', () => {
    renderList([]);
    expect(screen.getByText('暂无租户')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: '分页' })).not.toBeInTheDocument();
  });

  it('links to the next page and disables previous on the first page', () => {
    renderList(rows, { page: 0, size: 2, total: 5 });
    const pager = within(screen.getByRole('navigation', { name: '分页' }));
    expect(pager.getByText('第 1 / 3 页，共 5 条')).toBeInTheDocument();
    expect(pager.getByRole('link', { name: '下一页' })).toHaveAttribute(
      'href',
      '/tenants?page=1&size=2',
    );
    expect(pager.queryByRole('link', { name: '上一页' })).not.toBeInTheDocument();
    expect(pager.getByText('上一页')).toHaveAttribute('aria-disabled', 'true');
  });

  it('links both ways on a middle page', () => {
    renderList([rows[0]], { page: 1, size: 2, total: 5 });
    expect(screen.getByRole('link', { name: '上一页' })).toHaveAttribute(
      'href',
      '/tenants?page=0&size=2',
    );
    expect(screen.getByRole('link', { name: '下一页' })).toHaveAttribute(
      'href',
      '/tenants?page=2&size=2',
    );
  });

  it('disables next on the last page', () => {
    renderList([rows[0]], { page: 2, size: 2, total: 5 });
    expect(screen.queryByRole('link', { name: '下一页' })).not.toBeInTheDocument();
    expect(screen.getByText('下一页')).toHaveAttribute('aria-disabled', 'true');
  });

  it('is read-only: no buttons, forms or write links', () => {
    renderList(rows, { page: 0, size: 2, total: 5 });
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(document.querySelector('form, input, select, textarea')).toBeNull();
    for (const link of screen.getAllByRole('link')) {
      expect(link.getAttribute('href')).toMatch(/^\/tenants\?page=\d+&size=\d+$/);
    }
    expect(screen.queryByText(/新增|创建|编辑|删除/)).not.toBeInTheDocument();
  });
});
