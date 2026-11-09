import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { UserMenu } from '@/components/UserMenu';

const user = { displayName: '张三', accountUsername: 'zhangsan', tenantSlug: 'demo' };

describe('UserMenu', () => {
  it('shows the Display Name on a trigger that is at least 44px tall', () => {
    render(<UserMenu user={user} />);
    const trigger = screen.getByRole('button', { name: /张三/ });
    expect(trigger).toHaveClass('h-11');
  });

  it('shows Display Name, username and Tenant Slug once opened', async () => {
    render(<UserMenu user={user} />);
    await userEvent.setup().click(screen.getByRole('button', { name: /张三/ }));
    const menu = await screen.findByRole('menu');
    expect(within(menu).getByText('张三')).toBeInTheDocument();
    expect(within(menu).getByText('zhangsan')).toBeInTheDocument();
    expect(within(menu).getByText('demo')).toBeInTheDocument();
  });

  it('offers 退出登录 as a POST /logout form', async () => {
    render(<UserMenu user={user} />);
    await userEvent.setup().click(screen.getByRole('button', { name: /张三/ }));
    const logout = await screen.findByRole('menuitem', { name: '退出登录' });
    expect(logout.closest('form')).toHaveAttribute('action', '/logout');
    expect(logout.closest('form')).toHaveAttribute('method', 'post');
  });

  it('keeps the menu closed until the trigger is used', () => {
    render(<UserMenu user={user} />);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});
