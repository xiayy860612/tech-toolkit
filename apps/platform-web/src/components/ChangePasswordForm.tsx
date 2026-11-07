'use client';

import { useActionState, useState, type FormEvent } from 'react';
import { useFormStatus } from 'react-dom';
import type { ChangePasswordState } from '@/app/change-password/actions';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { PASSWORD_POLICY_MESSAGE } from '@/lib/format';

// Chinese only: the backend's explanation (state.detail) is never displayed.
const MESSAGES = {
  MISMATCH: '两次输入的新密码不一致',
  INVALID_CURRENT_PASSWORD: '当前密码错误',
  PASSWORD_POLICY: PASSWORD_POLICY_MESSAGE,
  UNAVAILABLE: '服务暂不可用，请稍后重试',
} as const;

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" disabled={pending} className="h-11 w-full">
      {pending ? '提交中…' : '修改密码'}
    </Button>
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
    <Card className="p-6">
      <form action={formAction} onSubmit={onSubmit} className="flex flex-col gap-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor="currentPassword">当前密码</Label>
          <Input
            id="currentPassword"
            name="currentPassword"
            type="password"
            required
            autoComplete="current-password"
            className="h-11"
          />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor="newPassword">新密码</Label>
          <Input
            id="newPassword"
            name="newPassword"
            type="password"
            required
            autoComplete="new-password"
            className="h-11"
          />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor="confirmPassword">确认新密码</Label>
          <Input
            id="confirmPassword"
            name="confirmPassword"
            type="password"
            required
            autoComplete="new-password"
            className="h-11"
          />
        </div>
        {message ? null : (
          <p className="text-sm text-muted-foreground">{PASSWORD_POLICY_MESSAGE}</p>
        )}
        {message ? (
          <Alert variant="destructive">
            <AlertDescription>{message}</AlertDescription>
          </Alert>
        ) : null}
        <SubmitButton />
      </form>
    </Card>
  );
}
