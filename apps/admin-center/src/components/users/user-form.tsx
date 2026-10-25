"use client";

import Link from "next/link";
import { useActionState, type ComponentProps } from "react";
import { useFormStatus } from "react-dom";
import { createUser } from "@/app/(shell)/users/new/actions";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { NewUserFormState } from "@/lib/validation/new-user";

type FieldProps = { id: string; label: string; hint: string; error?: string } & ComponentProps<typeof Input>;

/** 字段：出错时用原因替换说明文字，二者都通过 aria-describedby 关联。 */
function Field({ id, label, hint, error, ...props }: FieldProps) {
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
        aria-describedby={`${id}-msg`}
        {...props}
      />
      {error ? (
        <span id={`${id}-msg`} className="text-sm font-semibold text-destructive">
          {error}
        </span>
      ) : (
        <span id={`${id}-msg`} className="text-sm text-muted-foreground">
          {hint}
        </span>
      )}
    </div>
  );
}

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" size="lg" className="h-11 px-6" disabled={pending}>
      {pending ? "创建中…" : "创建用户"}
    </Button>
  );
}

/**
 * 新建用户表单（界面见 docs/system-design/admin-center/prototype）。
 * `idempotencyKey` 由页面每次渲染生成，随隐藏字段提交；动作返回新键时（UAM 明确拒绝后）改用新键。
 * 失败后 React 会把表单重置为默认值：用户名与账号名取回上次提交的值，密码框保持为空——密码从不回填。
 */
export function UserForm({ idempotencyKey }: { idempotencyKey: string }) {
  const [state, formAction] = useActionState<NewUserFormState, FormData>(createUser, {});

  return (
    <Card className="max-w-xl gap-4 p-4 sm:p-6">
      {state.formError && (
        <Alert variant="destructive" className="border-0 bg-destructive/10">
          <span className="col-start-2">{state.formError}</span>
        </Alert>
      )}
      <form action={formAction} noValidate className="grid gap-4">
        <input type="hidden" name="idempotencyKey" value={state.idempotencyKey ?? idempotencyKey} />
        <Field
          id="userName"
          label="用户名"
          hint="1–64 个字符，可以与其他用户重名。"
          type="text"
          defaultValue={state.values?.userName ?? ""}
          error={state.fieldErrors?.userName}
        />
        <Field
          id="accountName"
          label="账号名"
          hint="3–32 个字母、数字或 _ . -，不区分大小写，创建后不可修改。"
          type="text"
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          defaultValue={state.values?.accountName ?? ""}
          error={state.fieldErrors?.accountName}
        />
        <Field
          id="password"
          label="初始密码"
          hint="至少 10 位，且同时包含字母和数字。"
          type="password"
          autoComplete="new-password"
          error={state.fieldErrors?.password}
        />
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
