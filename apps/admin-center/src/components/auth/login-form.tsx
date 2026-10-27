"use client";

import { useActionState, type ComponentProps } from "react";
import { useFormStatus } from "react-dom";
import { login } from "@/app/(auth)/login/actions";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { LoginFormState } from "@/lib/validation/login";

function Field({ id, label, error, ...props }: { id: string; label: string; error?: string } & ComponentProps<typeof Input>) {
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id} className="font-semibold">
        {label}
      </Label>
      <Input
        id={id}
        name={id}
        className="h-11"
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-msg` : undefined}
        {...props}
      />
      {error && (
        <span id={`${id}-msg`} className="text-sm font-semibold text-destructive">
          {error}
        </span>
      )}
    </div>
  );
}

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" size="lg" className="h-11 w-full" disabled={pending}>
      {pending ? "登录中…" : "登录"}
    </Button>
  );
}

/**
 * 登录表单（界面见 docs/system-design/admin-center/prototype）。
 * 失败后 React 会把表单重置为默认值：账号名取回上次提交的值，密码框保持为空——密码从不回填。
 */
export function LoginForm({ next, notice }: { next: string; notice?: string }) {
  const [state, formAction] = useActionState<LoginFormState, FormData>(login, {});

  return (
    <Card>
      <CardContent className="grid gap-4">
        <div className="text-center">
          <h1 className="text-xl font-semibold">Admin Center</h1>
          <p className="mt-1 text-sm text-muted-foreground">请使用账号登录</p>
        </div>
        {notice && (
          <Alert role="status" className="border-0 bg-warning text-warning-foreground">
            <span className="col-start-2">{notice}</span>
          </Alert>
        )}
        {state.formError && (
          <Alert variant="destructive" className="border-0 bg-destructive/10">
            <span className="col-start-2">{state.formError}</span>
          </Alert>
        )}
        <form action={formAction} noValidate className="grid gap-4">
          <input type="hidden" name="next" value={next} />
          <Field
            id="accountName"
            label="账号名"
            type="text"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            defaultValue={state.accountName ?? ""}
            error={state.fieldErrors?.accountName}
          />
          <Field
            id="password"
            label="密码"
            type="password"
            autoComplete="current-password"
            error={state.fieldErrors?.password}
          />
          <SubmitButton />
        </form>
      </CardContent>
    </Card>
  );
}
