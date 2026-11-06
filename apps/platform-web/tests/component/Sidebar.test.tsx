import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

const nav = vi.hoisted(() => ({ pathname: '/tenants' }));
vi.mock('next/navigation', () => ({ usePathname: () => nav.pathname }));

import { Sidebar } from '@/components/Sidebar';

function renderAt(pathname: string) {
  nav.pathname = pathname;
  render(<Sidebar />);
  return within(screen.getByRole('navigation', { name: '主导航' }));
}

describe('Sidebar', () => {
  it('has exactly three items in a fixed order', () => {
    const navigation = renderAt('/tenants');
    const links = navigation.getAllByRole('link');
    expect(links.map((link) => link.textContent)).toEqual(['租户管理', '模块管理', '用户管理']);
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/tenants',
      '/modules',
      '/users',
    ]);
  });

  it.each([
    ['/tenants', '租户管理'],
    ['/modules', '模块管理'],
    ['/users', '用户管理'],
  ])('highlights the current item at %s', (pathname, label) => {
    const navigation = renderAt(pathname);
    const current = navigation.getAllByRole('link').filter((l) => l.hasAttribute('aria-current'));
    expect(current).toHaveLength(1);
    expect(current[0]).toHaveTextContent(label);
    expect(current[0]).toHaveAttribute('aria-current', 'page');
  });

  it('keeps the item highlighted on nested paths', () => {
    const navigation = renderAt('/users/123');
    expect(navigation.getByRole('link', { name: '用户管理' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  it('highlights nothing for an unrelated path', () => {
    const navigation = renderAt('/change-password');
    expect(navigation.getAllByRole('link').some((l) => l.hasAttribute('aria-current'))).toBe(false);
  });
});
