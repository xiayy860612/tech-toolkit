import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DataTable, type Column } from '@/components/DataTable';

interface Row {
  name: string;
}

const columns: Column<Row>[] = [{ header: '名称', cell: (row) => row.name }];

function renderTable(
  rows: Row[],
  pagination: { page: number; size: number; total: number } = {
    page: 0,
    size: 2,
    total: rows.length,
  },
) {
  return render(
    <DataTable
      caption="租户列表"
      columns={columns}
      rows={rows}
      emptyText="暂无租户"
      basePath="/tenants"
      {...pagination}
    />,
  );
}

describe('DataTable', () => {
  it('renders headers and rows', () => {
    renderTable([{ name: 'acme' }, { name: 'globex' }]);
    expect(screen.getByRole('table', { name: '租户列表' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '名称' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'acme' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'globex' })).toBeInTheDocument();
  });

  it('shows the empty-state text and no table when there are no rows', () => {
    renderTable([]);
    expect(screen.getByText('暂无租户')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: '分页' })).not.toBeInTheDocument();
  });

  it('links to the next page and disables previous on the first page', () => {
    renderTable([{ name: 'a' }, { name: 'b' }], { page: 0, size: 2, total: 5 });
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
    renderTable([{ name: 'c' }], { page: 1, size: 2, total: 5 });
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
    renderTable([{ name: 'e' }], { page: 2, size: 2, total: 5 });
    expect(screen.queryByRole('link', { name: '下一页' })).not.toBeInTheDocument();
    expect(screen.getByText('下一页')).toHaveAttribute('aria-disabled', 'true');
  });

  it('is read-only: no buttons, forms or write links', () => {
    renderTable([{ name: 'a' }, { name: 'b' }], { page: 0, size: 2, total: 5 });
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(document.querySelector('form, input, select, textarea')).toBeNull();
    for (const link of screen.getAllByRole('link')) {
      expect(link.getAttribute('href')).toMatch(/^\/tenants\?page=\d+&size=\d+$/);
    }
    expect(screen.queryByText(/新增|创建|编辑|删除/)).not.toBeInTheDocument();
  });
});
