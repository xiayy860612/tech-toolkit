'use client';

import { useActionState } from 'react';
import { useFormStatus } from 'react-dom';
import type { LoginState } from '@/app/login/actions';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

const MESSAGES: Record<NonNullable<LoginState['error']>, string> = {
  INVALID_CREDENTIALS: '用户名或密码错误，如多次失败请稍后再试',
  MODULE_NOT_SUBSCRIBED: '无访问权限',
  UNAVAILABLE: '服务暂不可用，请稍后重试',
};

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" disabled={pending} className="h-11 w-full">
      {pending ? '登录中…' : '登录'}
    </Button>
  );
}

interface LoginFormProps {
  action: (previous: LoginState, data: FormData) => Promise<LoginState>;
  next?: string;
}

export function LoginForm({ action, next }: LoginFormProps) {
  const [state, formAction] = useActionState(action, {} as LoginState);
  return (
    <Card className="p-6">
      <form action={formAction} className="flex flex-col gap-4">
        {next ? <input type="hidden" name="next" value={next} /> : null}
        <div className="flex flex-col gap-2">
          <Label htmlFor="tenantSlug">租户标识</Label>
          <Input
            id="tenantSlug"
            name="tenantSlug"
            type="text"
            required
            autoComplete="organization"
            defaultValue={state.tenantSlug}
            className="h-11"
          />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor="username">用户名</Label>
          <Input
            id="username"
            name="username"
            type="text"
            required
            autoComplete="username"
            defaultValue={state.username}
            className="h-11"
          />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor="password">密码</Label>
          <Input
            id="password"
            name="password"
            type="password"
            required
            autoComplete="current-password"
            className="h-11"
          />
        </div>
        {state.error ? (
          <Alert variant="destructive">
            <AlertDescription>{MESSAGES[state.error]}</AlertDescription>
          </Alert>
        ) : null}
        <SubmitButton />
      </form>
    </Card>
  );
}
