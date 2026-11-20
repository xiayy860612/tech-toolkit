"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { apiRequest } from "@/lib/api/client";
import { ApiError, errorMessage } from "@/lib/api/errors";
import type { BrowserMe } from "@/lib/api/types";
import { safeNext } from "@/lib/navigation";
import { loginSchema, validate } from "@/lib/validation";

export function LoginForm({ next }: { next?: string }) {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitError(null);
    const result = validate(loginSchema, { username, password });
    if (!result.ok) {
      setErrors(result.errors);
      return;
    }
    setErrors({});
    setLoading(true);
    try {
      const me = await apiRequest<BrowserMe>("/auth/login", {
        method: "POST",
        body: result.data,
      });
      router.replace(me.mustChangePassword ? "/change-password" : safeNext(next));
      router.refresh();
    } catch (error) {
      setSubmitError(errorMessage(error instanceof ApiError ? error.code : undefined));
      setLoading(false);
    }
  }

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
      <div>
        <h1 className="text-2xl font-semibold">登录</h1>
        <p className="mt-1 text-sm text-text-muted">使用用户名和密码登录租户管理中心</p>
      </div>
      <Field label="用户名" error={errors.username}>
        {(p) => (
          <Input
            {...p}
            name="username"
            autoComplete="username"
            autoFocus
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        )}
      </Field>
      <Field label="密码" error={errors.password}>
        {(p) => (
          <Input
            {...p}
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        )}
      </Field>
      {submitError && (
        <p role="alert" className="rounded-control bg-danger-bg px-3 py-2 text-sm text-danger">
          {submitError}
        </p>
      )}
      <Button type="submit" size="lg" loading={loading}>
        登录
      </Button>
    </form>
  );
}
