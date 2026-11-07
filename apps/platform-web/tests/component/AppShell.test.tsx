import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

const nav = vi.hoisted(() => ({ pathname: '/tenants' }));
vi.mock('next/navigation', () => ({ usePathname: () => nav.pathname }));

import { AppShell } from '@/components/AppShell';

function renderAt(pathname: string) {
  nav.pathname = pathname;
  return render(
    <AppShell>
      <p>content</p>
    </AppShell>,
  );
}

function sidebar() {
  return screen.getByRole('navigation', { name: '主导航' });
}

describe('AppShell desktop sidebar', () => {
  it('shows exactly three items in a fixed order', () => {
    renderAt('/tenants');
    const links = within(sidebar()).getAllByRole('link');
    expect(links.map((link) => link.textContent)).toEqual(['租户管理', '模块管理', '用户管理']);
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/tenants',
      '/modules',
      '/users',
    ]);
  });

  it('is only displayed from the md breakpoint up', () => {
    renderAt('/tenants');
    const aside = sidebar().closest('aside');
    expect(aside).toHaveClass('hidden', 'md:flex');
  });

  it.each([
    ['/tenants', '租户管理'],
    ['/modules', '模块管理'],
    ['/users', '用户管理'],
    ['/users/123', '用户管理'],
  ])('marks the current item at %s', (pathname, label) => {
    renderAt(pathname);
    const current = within(sidebar())
      .getAllByRole('link')
      .filter((link) => link.hasAttribute('aria-current'));
    expect(current).toHaveLength(1);
    expect(current[0]).toHaveTextContent(label);
    expect(current[0]).toHaveAttribute('aria-current', 'page');
  });

  it('marks nothing for an unrelated path', () => {
    renderAt('/change-password');
    expect(
      within(sidebar())
        .getAllByRole('link')
        .some((link) => link.hasAttribute('aria-current')),
    ).toBe(false);
  });

  it('has a POST logout form in the sidebar', () => {
    renderAt('/tenants');
    const logout = within(sidebar().closest('aside')!).getByRole('button', { name: '退出登录' });
    expect(logout.closest('form')).toHaveAttribute('action', '/logout');
    expect(logout.closest('form')).toHaveAttribute('method', 'post');
  });

  it('renders the page content in main', () => {
    renderAt('/tenants');
    expect(within(screen.getByRole('main')).getByText('content')).toBeInTheDocument();
  });
});

describe('AppShell narrow top bar', () => {
  it('has a menu button in a header that is hidden from md up', () => {
    renderAt('/tenants');
    const button = screen.getByRole('button', { name: '打开菜单' });
    expect(button.closest('header')).toHaveClass('md:hidden');
    expect(button).toHaveClass('size-11');
  });

  it('opens a Sheet with three navigation items and the logout form', async () => {
    renderAt('/modules');
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '打开菜单' }));

    const dialog = await screen.findByRole('dialog');
    const menu = within(dialog).getByRole('navigation', { name: '主导航' });
    const links = within(menu).getAllByRole('link');
    expect(links.map((link) => link.textContent)).toEqual(['租户管理', '模块管理', '用户管理']);
    expect(within(menu).getByRole('link', { name: '模块管理' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    const logout = within(dialog).getByRole('button', { name: '退出登录' });
    expect(logout.closest('form')).toHaveAttribute('action', '/logout');
    expect(logout.closest('form')).toHaveAttribute('method', 'post');
  });

  it('closes the Sheet when an item is chosen', async () => {
    renderAt('/tenants');
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '打开菜单' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('link', { name: '用户管理' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('does not render the Sheet navigation while closed', () => {
    renderAt('/tenants');
    expect(screen.getAllByRole('navigation', { name: '主导航' })).toHaveLength(1);
  });
});
