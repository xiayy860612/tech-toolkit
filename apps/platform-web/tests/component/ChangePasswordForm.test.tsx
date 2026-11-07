import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ChangePasswordForm } from '@/components/ChangePasswordForm';
import type { ChangePasswordState } from '@/app/change-password/actions';

function setup(result: ChangePasswordState | Promise<ChangePasswordState> = {}) {
  const action = vi.fn<
    (previous: ChangePasswordState, data: FormData) => Promise<ChangePasswordState>
  >(async () => result);
  render(<ChangePasswordForm action={action} />);
  return action;
}

async function fill(newPassword: string, confirmPassword: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('当前密码'), 'old-password');
  await user.type(screen.getByLabelText('新密码'), newPassword);
  await user.type(screen.getByLabelText('确认新密码'), confirmPassword);
  await user.click(screen.getByRole('button', { name: '修改密码' }));
}

describe('ChangePasswordForm', () => {
  it('is a Card with at least 44px tall controls', () => {
    setup();
    const form = screen.getByRole('button', { name: '修改密码' }).closest('form');
    expect(form?.closest('[data-slot="card"]')).not.toBeNull();
    for (const label of ['当前密码', '新密码', '确认新密码']) {
      expect(screen.getByLabelText(label)).toHaveClass('h-11');
    }
    expect(screen.getByRole('button', { name: '修改密码' })).toHaveClass('h-11');
  });

  it('has labelled fields and a password policy hint', () => {
    setup();
    expect(screen.getByLabelText('当前密码')).toHaveAttribute('name', 'currentPassword');
    expect(screen.getByLabelText('新密码')).toHaveAttribute('name', 'newPassword');
    expect(screen.getByLabelText('确认新密码')).toHaveAttribute('name', 'confirmPassword');
    expect(screen.getByLabelText('新密码')).toHaveAttribute('autocomplete', 'new-password');
    expect(screen.getByText(/至少 12 位/)).toBeInTheDocument();
  });

  it('submits when both new password entries match', async () => {
    const action = setup();
    await fill('brand-new-password', 'brand-new-password');

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    const data = action.mock.calls[0][1];
    expect(data.get('currentPassword')).toBe('old-password');
    expect(data.get('newPassword')).toBe('brand-new-password');
  });

  it('blocks submission and explains when the two entries differ', async () => {
    const action = setup();
    await fill('brand-new-password', 'another-password');

    expect(await screen.findByRole('alert')).toHaveTextContent('两次输入的新密码不一致');
    expect(action).not.toHaveBeenCalled();
  });

  it('clears the mismatch message after a correct resubmission', async () => {
    const action = setup();
    const user = userEvent.setup();
    await fill('brand-new-password', 'x');
    expect(await screen.findByRole('alert')).toBeInTheDocument();

    const confirm = screen.getByLabelText('确认新密码');
    await user.clear(confirm);
    await user.type(confirm, 'brand-new-password');
    await user.click(screen.getByRole('button', { name: '修改密码' }));

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });

  it('shows only the fixed Chinese rule for a password policy violation', async () => {
    setup({ error: 'PASSWORD_POLICY', detail: 'at least 12 characters' });
    await fill('short', 'short');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('新密码至少 12 位，不超过 128 位，且不得与用户名相同');
    expect(alert).not.toHaveTextContent(/[a-z]{3}/i);
  });

  it('shows the same rule when the backend sends no detail', async () => {
    setup({ error: 'PASSWORD_POLICY' });
    await fill('short', 'short');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '新密码至少 12 位，不超过 128 位，且不得与用户名相同',
    );
  });

  it('shows a wrong-current-password message', async () => {
    setup({ error: 'INVALID_CURRENT_PASSWORD' });
    await fill('brand-new-password', 'brand-new-password');
    expect(await screen.findByRole('alert')).toHaveTextContent('当前密码错误');
  });

  it('shows the server-side mismatch message', async () => {
    setup({ error: 'MISMATCH' });
    await fill('brand-new-password', 'brand-new-password');
    expect(await screen.findByRole('alert')).toHaveTextContent('两次输入的新密码不一致');
  });

  it('shows an unavailable message', async () => {
    setup({ error: 'UNAVAILABLE' });
    await fill('brand-new-password', 'brand-new-password');
    expect(await screen.findByRole('alert')).toHaveTextContent('服务暂不可用');
  });

  it('disables the submit button while pending', async () => {
    let finish: (value: ChangePasswordState) => void = () => {};
    setup(new Promise<ChangePasswordState>((resolve) => (finish = resolve)));
    await fill('brand-new-password', 'brand-new-password');

    expect(await screen.findByRole('button', { name: '提交中…' })).toBeDisabled();
    finish({});
    await waitFor(() => expect(screen.getByRole('button', { name: '修改密码' })).toBeEnabled());
  });
});
