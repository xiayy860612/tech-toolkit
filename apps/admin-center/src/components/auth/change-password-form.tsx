"use client";

import Link from "next/link";
import { useActionState, type ComponentProps } from "react";
import { useFormStatus } from "react-dom";
import { changePassword } from "@/app/(shell)/account/password/actions";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { ChangePasswordFormState } from "@/lib/validation/password";

type FieldProps = { id: string; label: string; hint?: string; error?: string } & ComponentProps<typeof Input>;

/** 字段：出错时用原因替换说明文字，二者都通过 aria-describedby 关联。 */
function Field({ id, label, hint, error, ...props }: FieldProps) {
  const message = error ?? hint;
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id} className="font-semibold">
        {label}
      </Label>
      <Input
        id={id}
        name={id}
        type="password"
        className="h-11"
        aria-invalid={error ? true : undefined}
        aria-describedby={message ? `${id}-msg` : undefined}
        {...props}
      />
      {message && (
        <span id={`${id}-msg`} className={error ? "text-sm font-semibold text-destructive" : "text-sm text-muted-foreground"}>
          {message}
        </span>
      )}
    </div>
  );
}

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" size="lg" className="h-11 px-6" disabled={pending}>
      {pending ? "修改中…" : "修改密码"}
    </Button>
  );
}

/**
 * 修改密码表单（界面见 specs/002-admin-center-uam/prototype）。
 * 提交后 React 会把表单重置为默认值：成功与失败都一样，三个密码框保持为空——密码从不回填。
 * 账号不是表单字段：服务端只改当前登录所用的账号。
 */
export function ChangePasswordForm() {
  const [state, formAction] = useActionState<ChangePasswordFormState, FormData>(changePassword, {});

  return (
    <Card className="max-w-xl gap-4 p-4 sm:p-6">
      {state.success && (
        <Alert role="status">
          <span className="col-start-2">密码已修改，当前会话保持有效。</span>
        </Alert>
      )}
      {state.formError && (
        <Alert variant="destructive" className="border-0 bg-destructive/10">
          <span className="col-start-2">{state.formError}</span>
        </Alert>
      )}
      <form action={formAction} noValidate className="grid gap-4">
        <Field id="currentPassword" label="当前密码" autoComplete="current-password" error={state.fieldErrors?.currentPassword} />
        <Field
          id="newPassword"
          label="新密码"
          hint="至少 10 位，且同时包含字母和数字，不能与当前密码相同。"
          autoComplete="new-password"
          error={state.fieldErrors?.newPassword}
        />
        <Field id="confirmPassword" label="确认新密码" autoComplete="new-password" error={state.fieldErrors?.confirmPassword} />
        <div className="flex flex-wrap gap-2">
          <SubmitButton />
          <Button asChild variant="outline" size="lg" className="h-11 px-6">
            <Link href="/users">取消</Link>
          </Button>
        </div>
      </form>
    </Card>
  );
}
