import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  requireSession: vi.fn(),
  redirect: vi.fn(),
  pathname: '/tenants',
}));

vi.mock('@/server/auth', () => ({ requireSession: mocks.requireSession }));
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
  mocks.redirect.mockReset();
});

describe('RootPage', () => {
  it('redirects / to /tenants', () => {
    RootPage();
    expect(mocks.redirect).toHaveBeenCalledWith('/tenants');
  });
});

describe('ConsoleLayout', () => {
  it('requires a Session and renders the sidebar, logout form and content', async () => {
    mocks.requireSession.mockResolvedValue({});
    render(await ConsoleLayout({ children: <p>content</p> }));

    expect(mocks.requireSession).toHaveBeenCalledOnce();
    expect(screen.getByRole('navigation', { name: '主导航' })).toBeInTheDocument();
    expect(screen.getByText('content')).toBeInTheDocument();
    const logout = screen.getByRole('button', { name: '退出登录' });
    expect(logout.closest('form')).toHaveAttribute('action', '/logout');
    expect(logout.closest('form')).toHaveAttribute('method', 'post');
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
