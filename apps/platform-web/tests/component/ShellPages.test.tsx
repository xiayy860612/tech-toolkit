import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  requireSession: vi.fn(),
  me: vi.fn(),
  redirect: vi.fn(),
  pathname: '/tenants',
}));

vi.mock('@/server/auth', () => ({
  requireSession: mocks.requireSession,
  runWithSession: async (call: (client: unknown, session: { token: string }) => Promise<unknown>) =>
    call({ me: mocks.me }, { token: 'tk_secret' }),
}));
vi.mock('next/navigation', () => ({
  redirect: mocks.redirect,
  usePathname: () => mocks.pathname,
}));
vi.mock('@/app/login/actions', () => ({ loginAction: vi.fn() }));
vi.mock('@/app/change-password/actions', () => ({ changePasswordAction: vi.fn() }));

import ChangePasswordPage from '@/app/change-password/page';
import ConsoleLayout from '@/app/(console)/layout';
import LoginPage from '@/app/login/page';
import RootPage from '@/app/page';

beforeEach(() => {
  mocks.requireSession.mockReset();
  mocks.me.mockReset();
  mocks.redirect.mockReset();
});

describe('RootPage', () => {
  it('redirects / to /tenants', () => {
    RootPage();
    expect(mocks.redirect).toHaveBeenCalledWith('/tenants');
  });
});

describe('ConsoleLayout', () => {
  it('loads the caller with the Session token and renders sidebar, user menu and content', async () => {
    mocks.me.mockResolvedValue({
      tenantUserId: 'tu_1',
      displayName: '张三',
      accountUsername: 'zhangsan',
      tenantSlug: 'demo',
      mustChangePassword: false,
    });
    render(await ConsoleLayout({ children: <p>content</p> }));

    expect(mocks.me).toHaveBeenCalledWith('tk_secret');
    expect(screen.getByRole('navigation', { name: '主导航' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /张三/ })).toBeInTheDocument();
    expect(screen.getByText('content')).toBeInTheDocument();
  });
});

describe('LoginPage', () => {
  it('renders the form and carries a safe next path', async () => {
    const { container } = render(
      await LoginPage({ searchParams: Promise.resolve({ next: '/users?page=1' }) }),
    );
    expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument();
    expect(container.querySelector('input[name="next"]')).toHaveValue('/users?page=1');
  });

  it('uses the first value of a repeated next and rejects unsafe ones', async () => {
    const { container } = render(
      await LoginPage({ searchParams: Promise.resolve({ next: ['//evil.example', '/users'] }) }),
    );
    expect(container.querySelector('input[name="next"]')).toHaveValue('/tenants');
  });
});

describe('ChangePasswordPage', () => {
  it('explains the forced change on first login', async () => {
    mocks.requireSession.mockResolvedValue({ mustChangePassword: true });
    render(await ChangePasswordPage());
    expect(mocks.requireSession).toHaveBeenCalledWith({ allowMustChangePassword: true });
    expect(screen.getByText('首次登录需先修改初始密码。')).toBeInTheDocument();
    expect(screen.getByLabelText('新密码')).toBeInTheDocument();
  });

  it('omits the notice for a voluntary change', async () => {
    mocks.requireSession.mockResolvedValue({ mustChangePassword: false });
    render(await ChangePasswordPage());
    expect(screen.queryByText('首次登录需先修改初始密码。')).not.toBeInTheDocument();
  });
});
