'use client';

import { useActionState } from 'react';
import { useFormStatus } from 'react-dom';
import type { LoginState } from '@/app/login/actions';

const MESSAGES: Record<NonNullable<LoginState['error']>, string> = {
  INVALID_CREDENTIALS: '用户名或密码错误，如多次失败请稍后再试',
  MODULE_NOT_SUBSCRIBED: '无访问权限',
  UNAVAILABLE: '服务暂不可用，请稍后重试',
};

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <button type="submit" disabled={pending}>
      {pending ? '登录中…' : '登录'}
    </button>
  );
}

interface LoginFormProps {
  action: (previous: LoginState, data: FormData) => Promise<LoginState>;
  next?: string;
}

export function LoginForm({ action, next }: LoginFormProps) {
  const [state, formAction] = useActionState(action, {} as LoginState);
  return (
    <form action={formAction} className="card">
      {next ? <input type="hidden" name="next" value={next} /> : null}
      <label>
        租户标识
        <input
          name="tenantSlug"
          type="text"
          required
          autoComplete="organization"
          defaultValue={state.tenantSlug}
        />
      </label>
      <label>
        用户名
        <input
          name="username"
          type="text"
          required
          autoComplete="username"
          defaultValue={state.username}
        />
      </label>
      <label>
        密码
        <input name="password" type="password" required autoComplete="current-password" />
      </label>
      {state.error ? (
        <p role="alert" className="error">
          {MESSAGES[state.error]}
        </p>
      ) : null}
      <SubmitButton />
    </form>
  );
}
