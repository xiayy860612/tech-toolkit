import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  client: {
    listTenants: vi.fn(),
    listModuleSubscriptions: vi.fn(),
    listUsers: vi.fn(),
  },
}));

vi.mock('@/server/auth', () => ({
  runWithSession: async (call: (client: unknown, session: { token: string }) => Promise<unknown>) =>
    call(mocks.client, { token: 'tk_secret' }),
}));

import ModulesPage from '@/app/(console)/modules/page';
import TenantsPage from '@/app/(console)/tenants/page';
import UsersPage from '@/app/(console)/users/page';

const emptyPage = { items: [], page: 0, size: 20, total: 0 };

async function renderPage(
  Page: (props: {
    searchParams: Promise<Record<string, string | string[] | undefined>>;
  }) => Promise<React.JSX.Element>,
  searchParams: Record<string, string> = {},
) {
  render(await Page({ searchParams: Promise.resolve(searchParams) }));
}

function headers() {
  return screen.getAllByRole('columnheader').map((header) => header.textContent);
}

function cellsOfRow(index: number) {
  const rows = screen.getAllByRole('row');
  return within(rows[index + 1])
    .getAllByRole('cell')
    .map((cell) => cell.textContent);
}

beforeEach(() => {
  for (const fn of Object.values(mocks.client)) fn.mockReset();
});

describe('TenantsPage', () => {
  it('lists Tenant Slug, name, status and created time', async () => {
    mocks.client.listTenants.mockResolvedValue({
      items: [
        { slug: 'acme', name: 'Acme Corp', status: 'ACTIVE', createdAt: '2030-01-02T03:04:05Z' },
      ],
      page: 0,
      size: 20,
      total: 1,
    });

    await renderPage(TenantsPage);

    expect(screen.getByRole('heading', { name: '租户管理' })).toBeInTheDocument();
    expect(headers()).toEqual(['租户标识', '名称', '状态', '创建时间']);
    expect(cellsOfRow(0)).toEqual(['acme', 'Acme Corp', '正常', '2030-01-02 03:04']);
  });

  it('requests the page and size from searchParams using the Opaque Token', async () => {
    mocks.client.listTenants.mockResolvedValue(emptyPage);
    await renderPage(TenantsPage, { page: '3', size: '50' });
    expect(mocks.client.listTenants).toHaveBeenCalledWith('tk_secret', { page: 3, size: 50 });
  });

  it('shows the empty state', async () => {
    mocks.client.listTenants.mockResolvedValue(emptyPage);
    await renderPage(TenantsPage);
    expect(screen.getByText('暂无租户')).toBeInTheDocument();
  });

  it('never renders the Opaque Token', async () => {
    mocks.client.listTenants.mockResolvedValue(emptyPage);
    await renderPage(TenantsPage);
    expect(document.body.innerHTML).not.toContain('tk_secret');
  });
});

describe('ModulesPage', () => {
  it('lists moduleCode, Tenant Slug, status and subscribed time', async () => {
    mocks.client.listModuleSubscriptions.mockResolvedValue({
      items: [
        {
          moduleCode: 'platform',
          tenantSlug: 'acme',
          status: 'SUBSCRIBED',
          subscribedAt: '2030-02-03T04:05:06Z',
        },
      ],
      page: 0,
      size: 20,
      total: 1,
    });

    await renderPage(ModulesPage);

    expect(screen.getByRole('heading', { name: '模块管理' })).toBeInTheDocument();
    expect(headers()).toEqual(['模块', '租户', '订阅状态', '订阅时间']);
    expect(cellsOfRow(0)).toEqual(['platform', 'acme', '已订阅', '2030-02-03 04:05']);
  });

  it('shows the empty state', async () => {
    mocks.client.listModuleSubscriptions.mockResolvedValue(emptyPage);
    await renderPage(ModulesPage);
    expect(screen.getByText('暂无模块订阅')).toBeInTheDocument();
  });
});

describe('UsersPage', () => {
  it('lists Display Name, usernames, Tenant Slug, status and created time', async () => {
    mocks.client.listUsers.mockResolvedValue({
      items: [
        {
          tenantUserId: 'tu_1',
          displayName: '张三',
          accountUsernames: ['admin', 'zhangsan'],
          tenantSlug: 'acme',
          status: 'ACTIVE',
          createdAt: '2030-03-04T05:06:07Z',
        },
      ],
      page: 0,
      size: 20,
      total: 1,
    });

    await renderPage(UsersPage);

    expect(screen.getByRole('heading', { name: '用户管理' })).toBeInTheDocument();
    expect(headers()).toEqual(['显示名', '用户名', '租户', '状态', '创建时间']);
    expect(cellsOfRow(0)).toEqual(['张三', 'admin, zhangsan', 'acme', '正常', '2030-03-04 05:06']);
  });

  it('shows a dash in the username cell for a User without an Account', async () => {
    mocks.client.listUsers.mockResolvedValue({
      items: [
        {
          tenantUserId: 'tu_2',
          displayName: '李四',
          accountUsernames: [],
          tenantSlug: 'acme',
          status: 'ACTIVE',
          createdAt: '2030-03-04T05:06:07Z',
        },
      ],
      page: 0,
      size: 20,
      total: 1,
    });

    await renderPage(UsersPage);

    expect(cellsOfRow(0)).toEqual(['李四', '—', 'acme', '正常', '2030-03-04 05:06']);
  });

  it('shows the empty state and nothing credential-like', async () => {
    mocks.client.listUsers.mockResolvedValue(emptyPage);
    await renderPage(UsersPage);
    expect(screen.getByText('暂无用户')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/password|密码|token/i);
  });
});
