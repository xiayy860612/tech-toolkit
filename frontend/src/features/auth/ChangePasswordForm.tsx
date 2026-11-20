"use client";

import { Check, Circle } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { apiRequest } from "@/lib/api/client";
import { ApiError, errorMessage } from "@/lib/api/errors";
import { hardNavigate } from "@/lib/navigation";
import { changePasswordSchema, checkPassword, validate } from "@/lib/validation";

export function ChangePasswordForm() {
  const [currentPassword, setCurrent] = useState("");
  const [newPassword, setNew] = useState("");
  const [confirm, setConfirm] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const rules = checkPassword(newPassword).rules;

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitError(null);
    const result = validate(changePasswordSchema, { currentPassword, newPassword, confirm });
    if (!result.ok) {
      setErrors(result.errors);
      return;
    }
    setErrors({});
    setLoading(true);
    try {
      await apiRequest("/auth/change-password", {
        method: "POST",
        body: { currentPassword, newPassword },
      });
      hardNavigate("/users"); // 整页跳转，让服务端守卫按最新状态（角色等）决定去向
    } catch (error) {
      if (error instanceof ApiError && error.status === 422) {
        setErrors({ currentPassword: "当前密码不正确" });
      } else {
        setSubmitError(errorMessage(error instanceof ApiError ? error.code : undefined));
      }
      setLoading(false);
    }
  }

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
      <Field label="当前密码" error={errors.currentPassword}>
        {(p) => (
          <Input
            {...p}
            type="password"
            autoComplete="current-password"
            value={currentPassword}
            onChange={(e) => setCurrent(e.target.value)}
          />
        )}
      </Field>
      <Field label="新密码" error={errors.newPassword}>
        {(p) => (
          <Input
            {...p}
            type="password"
            autoComplete="new-password"
            value={newPassword}
            onChange={(e) => setNew(e.target.value)}
          />
        )}
      </Field>
      <ul aria-label="密码规则" className="flex flex-col gap-1 text-sm">
        {rules.map((r) => (
          <li
            key={r.id}
            data-passed={r.passed}
            className={
              r.passed
                ? "flex items-center gap-2 text-success"
                : "flex items-center gap-2 text-text-muted"
            }
          >
            {r.passed ? (
              <Check className="size-4" aria-hidden="true" />
            ) : (
              <Circle className="size-4" aria-hidden="true" />
            )}
            {r.label}
          </li>
        ))}
      </ul>
      <Field label="确认新密码" error={errors.confirm}>
        {(p) => (
          <Input
            {...p}
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
          />
        )}
      </Field>
      {submitError && (
        <p role="alert" className="rounded-control bg-danger-bg px-3 py-2 text-sm text-danger">
          {submitError}
        </p>
      )}
      <Button type="submit" size="lg" loading={loading}>
        修改密码
      </Button>
    </form>
  );
}
