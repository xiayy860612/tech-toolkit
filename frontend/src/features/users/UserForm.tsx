"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { StickyActionBar } from "@/components/ui/sticky-action-bar";
import { apiRequest } from "@/lib/api/client";
import { ApiError, fieldErrorMap, messageOf } from "@/lib/api/errors";
import type { Role, User, UserWithTemporaryPassword } from "@/lib/api/types";
import { cn } from "@/lib/utils";
import { displayNameSchema, passwordSchema, usernameSchema } from "@/lib/validation";
import { TempPasswordDialog } from "./TempPasswordDialog";

type Props =
  { mode: "create" } | { mode: "edit"; user: User; onReload?: () => void; currentUserId?: number };

const ROLES: { value: Role; label: string; hint: string }[] = [
  { value: "TENANT_ADMIN", label: "租户管理员", hint: "可管理本租户的用户、账号与登录会话" },
  { value: "MEMBER", label: "普通用户", hint: "仅可登录并修改自己的密码" },
];

function check(
  errors: Record<string, string>,
  field: string,
  schema: {
    safeParse: (v: unknown) => { success: boolean; error?: { issues: { message: string }[] } };
  },
  value: string,
) {
  const result = schema.safeParse(value);
  if (!result.success) errors[field] = result.error?.issues[0]?.message ?? "格式不正确";
}

export function UserForm(props: Props) {
  const router = useRouter();
  const editing = props.mode === "edit" ? props.user : null;
  // 不能修改自己的角色（会让自己失去管理权限）；后端同样以 409 拒绝
  const ownRoleLocked =
    props.mode === "edit" &&
    props.currentUserId !== undefined &&
    props.currentUserId === props.user.id;

  const [displayName, setDisplayName] = useState(editing?.displayName ?? "");
  const [role, setRole] = useState<Role>(editing?.role ?? "MEMBER");
  const [username, setUsername] = useState("");
  const [temporaryPassword, setTemporaryPassword] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitError, setSubmitError] = useState<{ message: string; conflict: boolean } | null>(
    null,
  );
  const [loading, setLoading] = useState(false);
  const [issuedPassword, setIssuedPassword] = useState<{ password: string; userId: number } | null>(
    null,
  );

  const errorCount = Object.keys(errors).length;

  function validateAll(): Record<string, string> {
    const found: Record<string, string> = {};
    check(found, "displayName", displayNameSchema, displayName);
    if (!editing) {
      check(found, "username", usernameSchema, username);
      if (temporaryPassword) check(found, "temporaryPassword", passwordSchema, temporaryPassword);
    }
    return found;
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitError(null);
    const found = validateAll();
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    const base = {
      displayName: displayName.trim(),
      role,
    };
    setLoading(true);
    try {
      if (props.mode === "edit") {
        await apiRequest(`/users/${props.user.id}`, {
          method: "PATCH",
          body: { ...base, version: props.user.version },
        });
        router.push(`/users/${props.user.id}`);
      } else {
        const created = await apiRequest<UserWithTemporaryPassword>("/users", {
          method: "POST",
          body: {
            ...base,
            username,
            ...(temporaryPassword ? { temporaryPassword } : {}),
          },
        });
        const issued = created.temporaryPassword;
        if (issued) setIssuedPassword({ password: issued, userId: created.user.id });
        else router.push(`/users/${created.user.id}`);
      }
    } catch (error) {
      if (error instanceof ApiError && error.code === "USERNAME_TAKEN") {
        setErrors({ username: messageOf(error) });
      } else if (error instanceof ApiError && error.fieldErrors.length > 0) {
        setErrors(fieldErrorMap(error));
      } else {
        setSubmitError({
          message: messageOf(error),
          conflict: error instanceof ApiError && error.code === "VERSION_CONFLICT",
        });
      }
    } finally {
      setLoading(false);
    }
  }

  const reload = props.mode === "edit" ? props.onReload : undefined;

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
      <Card>
        <CardHeader>
          <CardTitle>基本信息</CardTitle>
        </CardHeader>
        <div className="grid gap-5 md:grid-cols-2">
          <Field label="姓名" required error={errors.displayName}>
            {(p) => (
              <Input
                {...p}
                maxLength={50}
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
              />
            )}
          </Field>
        </div>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>账号与角色</CardTitle>
        </CardHeader>
        <div className="flex flex-col gap-5">
          <fieldset>
            <legend className="mb-1.5 text-sm font-medium">角色</legend>
            {ownRoleLocked && <p className="mb-2 text-xs text-text-muted">不能修改自己的角色</p>}
            <div className="grid gap-3 md:grid-cols-2">
              {ROLES.map((r) => (
                <label
                  key={r.value}
                  className={cn(
                    "flex min-h-11 cursor-pointer gap-3 rounded-control border p-3",
                    role === r.value ? "border-accent bg-info-bg" : "border-input-border",
                  )}
                >
                  <input
                    type="radio"
                    name="role"
                    value={r.value}
                    checked={role === r.value}
                    disabled={ownRoleLocked}
                    onChange={() => setRole(r.value)}
                    className="mt-1"
                  />
                  <span>
                    <span className="block text-sm font-medium">{r.label}</span>
                    <span className="block text-xs text-text-muted">{r.hint}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
          {!editing && (
            <div className="grid gap-5 md:grid-cols-2">
              <Field label="登录用户名" required error={errors.username}>
                {(p) => (
                  <Input
                    {...p}
                    autoComplete="off"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                  />
                )}
              </Field>
              <Field
                label="临时密码"
                hint="留空则由系统生成；用户首次登录须修改"
                error={errors.temporaryPassword}
              >
                {(p) => (
                  <Input
                    {...p}
                    type="password"
                    autoComplete="new-password"
                    value={temporaryPassword}
                    onChange={(e) => setTemporaryPassword(e.target.value)}
                  />
                )}
              </Field>
            </div>
          )}
        </div>
      </Card>

      {submitError && (
        <div
          role="alert"
          className="flex items-center justify-between gap-3 rounded-control bg-danger-bg px-3 py-2 text-sm text-danger"
        >
          <span>{submitError.message}</span>
          {submitError.conflict && reload && (
            <Button variant="secondary" onClick={reload}>
              刷新
            </Button>
          )}
        </div>
      )}

      <StickyActionBar summary={errorCount > 0 ? `有 ${errorCount} 项校验未通过` : undefined}>
        <Link
          href={editing ? `/users/${editing.id}` : "/users"}
          className="inline-flex min-h-10 items-center rounded-control border border-input-border px-4 text-sm hover:bg-table-head"
        >
          取消
        </Link>
        <Button type="submit" loading={loading}>
          {editing ? "保存" : "创建用户"}
        </Button>
      </StickyActionBar>

      {issuedPassword && (
        <TempPasswordDialog
          password={issuedPassword.password}
          onClose={() => router.push(`/users/${issuedPassword.userId}`)}
        />
      )}
    </form>
  );
}
