import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { LoginForm } from '@/components/LoginForm';
import type { LoginState } from '@/app/login/actions';

function setup(result: LoginState | Promise<LoginState> = {}, next?: string) {
  const action = vi.fn<(previous: LoginState, data: FormData) => Promise<LoginState>>(
    async () => result,
  );
  render(<LoginForm action={action} next={next} />);
  return action;
}

async function fillAndSubmit() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('租户标识'), 'acme');
  await user.type(screen.getByLabelText('用户名'), 'admin');
  await user.type(screen.getByLabelText('密码'), 'secret-pw');
  await user.click(screen.getByRole('button', { name: '登录' }));
}

describe('LoginForm', () => {
  it('is a centered Card with at least 44px tall controls', () => {
    setup();
    const form = screen.getByRole('button', { name: '登录' }).closest('form');
    expect(form?.closest('[data-slot="card"]')).not.toBeNull();
    for (const label of ['租户标识', '用户名', '密码']) {
      expect(screen.getByLabelText(label)).toHaveClass('h-11');
    }
    expect(screen.getByRole('button', { name: '登录' })).toHaveClass('h-11');
  });

  it('has labelled tenantSlug, username and password fields', () => {
    setup();
    expect(screen.getByLabelText('租户标识')).toHaveAttribute('name', 'tenantSlug');
    expect(screen.getByLabelText('用户名')).toHaveAttribute('name', 'username');
    const password = screen.getByLabelText('密码');
    expect(password).toHaveAttribute('name', 'password');
    expect(password).toHaveAttribute('type', 'password');
    expect(password).toHaveAttribute('autocomplete', 'current-password');
  });

  it('submits the three fields and the next path', async () => {
    const action = setup({}, '/users?page=2');
    await fillAndSubmit();

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    const data = action.mock.calls[0][1];
    expect(data.get('tenantSlug')).toBe('acme');
    expect(data.get('username')).toBe('admin');
    expect(data.get('password')).toBe('secret-pw');
    expect(data.get('next')).toBe('/users?page=2');
  });

  it('shows no error before submission', () => {
    setup();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows the single unified message for wrong credentials', async () => {
    setup({ error: 'INVALID_CREDENTIALS' });
    await fillAndSubmit();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '用户名或密码错误，如多次失败请稍后再试',
    );
  });

  it('shows the no-access message when the Tenant has no platform subscription', async () => {
    setup({ error: 'MODULE_NOT_SUBSCRIBED' });
    await fillAndSubmit();
    expect(await screen.findByRole('alert')).toHaveTextContent('无访问权限');
  });

  it('shows an unavailable message for backend failures', async () => {
    setup({ error: 'UNAVAILABLE' });
    await fillAndSubmit();
    expect(await screen.findByRole('alert')).toHaveTextContent('服务暂不可用');
  });

  it('disables the submit button while the request is pending', async () => {
    let finish: (value: LoginState) => void = () => {};
    setup(new Promise<LoginState>((resolve) => (finish = resolve)));
    await fillAndSubmit();

    const button = await screen.findByRole('button', { name: '登录中…' });
    expect(button).toBeDisabled();

    finish({});
    await waitFor(() => expect(screen.getByRole('button', { name: '登录' })).toBeEnabled());
  });
});
