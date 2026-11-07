import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

const nav = vi.hoisted(() => ({ pathname: '/tenants' }));
vi.mock('next/navigation', () => ({ usePathname: () => nav.pathname }));

import { AppShell } from '@/components/AppShell';

const user = { displayName: '张三', accountUsername: 'zhangsan', tenantSlug: 'demo' };

function renderAt(pathname: string) {
  nav.pathname = pathname;
  return render(
    <AppShell user={user}>
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
    expect(aside).toHaveClass('hidden', 'md:block');
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

  it('has no logout control in the sidebar', () => {
    renderAt('/tenants');
    expect(
      within(sidebar().closest('aside')!).queryByRole('button', { name: '退出登录' }),
    ).not.toBeInTheDocument();
  });

  it('limits the content width inside main', () => {
    renderAt('/tenants');
    expect(screen.getByRole('main').firstElementChild).toHaveClass('max-w-6xl');
  });

  it('renders the page content in main', () => {
    renderAt('/tenants');
    expect(within(screen.getByRole('main')).getByText('content')).toBeInTheDocument();
  });
});

describe('AppShell narrow top bar', () => {
  it('has a menu button inside the top bar that is hidden from md up', () => {
    renderAt('/tenants');
    const button = screen.getByRole('button', { name: '打开菜单' });
    expect(button.closest('header')).toBe(screen.getByRole('banner'));
    expect(button.parentElement).toHaveClass('md:hidden');
    expect(button).toHaveClass('size-11');
  });

  it('keeps the top bar with the user menu at every width', () => {
    renderAt('/tenants');
    const banner = screen.getByRole('banner');
    expect(banner).not.toHaveClass('md:hidden');
    expect(within(banner).getByRole('button', { name: /张三/ })).toBeInTheDocument();
  });

  it('opens a Sheet with the three navigation items and no logout', async () => {
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
    expect(within(dialog).queryByRole('button', { name: '退出登录' })).not.toBeInTheDocument();
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
