'use client';

import { useActionState, useState, type FormEvent } from 'react';
import { useFormStatus } from 'react-dom';
import type { ChangePasswordState } from '@/app/change-password/actions';

const MESSAGES = {
  MISMATCH: '两次输入的新密码不一致',
  INVALID_CURRENT_PASSWORD: '当前密码错误',
  PASSWORD_POLICY: '新密码不符合密码策略',
  UNAVAILABLE: '服务暂不可用，请稍后重试',
} as const;

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <button type="submit" disabled={pending}>
      {pending ? '提交中…' : '修改密码'}
    </button>
  );
}

interface ChangePasswordFormProps {
  action: (previous: ChangePasswordState, data: FormData) => Promise<ChangePasswordState>;
}

export function ChangePasswordForm({ action }: ChangePasswordFormProps) {
  const [state, formAction] = useActionState(action, {} as ChangePasswordState);
  const [mismatch, setMismatch] = useState(false);

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    const data = new FormData(event.currentTarget);
    if (data.get('newPassword') !== data.get('confirmPassword')) {
      event.preventDefault();
      setMismatch(true);
    } else {
      setMismatch(false);
    }
  }

  const message = mismatch ? MESSAGES.MISMATCH : state.error ? MESSAGES[state.error] : null;

  return (
    <form action={formAction} onSubmit={onSubmit} className="card">
      <label>
        当前密码
        <input name="currentPassword" type="password" required autoComplete="current-password" />
      </label>
      <label>
        新密码
        <input name="newPassword" type="password" required autoComplete="new-password" />
      </label>
      <label>
        确认新密码
        <input name="confirmPassword" type="password" required autoComplete="new-password" />
      </label>
      <p className="hint">新密码至少 12 位，且不得与用户名相同。</p>
      {message ? (
        <p role="alert" className="error">
          {message}
          {!mismatch && state.error === 'PASSWORD_POLICY' && state.detail
            ? `：${state.detail}`
            : null}
        </p>
      ) : null}
      <SubmitButton />
    </form>
  );
}
