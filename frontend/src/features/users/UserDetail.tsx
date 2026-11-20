"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import { Switch } from "@/components/ui/switch";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { CredentialSection } from "@/features/credentials/CredentialSection";
import { apiRequest } from "@/lib/api/client";
import { ApiError, messageOf } from "@/lib/api/errors";
import type { User } from "@/lib/api/types";
import { formatDateTime, roleLabel, statusLabel } from "@/lib/format";

type State =
  | { kind: "loading" }
  | { kind: "error"; message: string; notFound: boolean }
  | { kind: "ok"; user: User };

export function UserDetail({ id, currentUserId }: { id: string; currentUserId?: number }) {
  const [state, setState] = useState<State>({ kind: "loading" });
  const [actionError, setActionError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  const load = useCallback(() => {
    setState({ kind: "loading" });
    apiRequest<User>(`/users/${id}`)
      .then((user) => setState({ kind: "ok", user }))
      .catch((error) =>
        setState({
          kind: "error",
          message: messageOf(error),
          notFound: error instanceof ApiError && error.status === 404,
        }),
      );
  }, [id]);

  useEffect(load, [load]);

  async function setStatus(user: User, action: "disable" | "enable") {
    setActionError(null);
    try {
      const updated = await apiRequest<User>(`/users/${user.id}/${action}`, { method: "POST" });
      setState({ kind: "ok", user: updated });
    } catch (error) {
      setActionError(messageOf(error));
    }
  }

  if (state.kind === "loading") {
    return (
      <div
        role="status"
        aria-label="加载中"
        className="h-40 animate-pulse rounded-card bg-table-head"
      />
    );
  }

  if (state.kind === "error") {
    return (
      <Card role="alert" className="flex flex-col items-center gap-3 p-10 text-center">
        <p>{state.message}</p>
        {state.notFound ? (
          <Link href="/users" className="text-accent hover:underline">
            返回用户列表
          </Link>
        ) : (
          <Button variant="secondary" onClick={load}>
            重试
          </Button>
        )}
      </Card>
    );
  }

  const { user } = state;
  const active = user.status === "ACTIVE";
  const isSelf = currentUserId !== undefined && currentUserId === user.id;

  return (
    <div className="flex flex-col gap-5">
      <Card>
        <CardHeader>
          <div className="flex min-w-0 flex-wrap items-center gap-3">
            <h1 className="text-2xl font-semibold">{user.displayName}</h1>
            <Badge tone={active ? "success" : "danger"}>{statusLabel(user.status)}</Badge>
          </div>
          <div className="flex flex-wrap items-center gap-4">
            {!isSelf && (
              <div className="flex items-center gap-2 text-sm">
                <Switch
                  aria-label="启用状态"
                  checked={active}
                  onCheckedChange={() =>
                    active ? setConfirming(true) : void setStatus(user, "enable")
                  }
                />
              </div>
            )}
            <Link
              href={`/users/${user.id}/edit`}
              className="inline-flex min-h-10 items-center rounded-control border border-input-border px-4 text-sm hover:bg-table-head"
            >
              编辑
            </Link>
          </div>
        </CardHeader>
        {actionError && (
          <p
            role="alert"
            className="mb-4 rounded-control bg-danger-bg px-3 py-2 text-sm text-danger"
          >
            {actionError}
          </p>
        )}
        <CardTitle className="sr-only">用户信息</CardTitle>
        <dl className="grid gap-x-8 gap-y-4 text-sm md:grid-cols-2">
          <Info label="角色">{roleLabel(user.role)}</Info>
          <Info label="最近登录">{formatDateTime(user.lastLoginAt, "从未登录")}</Info>
          <Info label="创建时间">{formatDateTime(user.createdAt)}</Info>
        </dl>
      </Card>

      <CredentialSection
        user={user}
        onChanged={(updated) => setState({ kind: "ok", user: updated })}
      />

      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={`停用 ${user.displayName}？`}
        description="停用后该用户将无法登录，已登录状态立即失效。"
        confirmLabel="确认停用"
        onConfirm={() => setStatus(user, "disable")}
      />
    </div>
  );
}

function Info({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-text-muted">{label}</dt>
      <dd className="mt-0.5 font-medium">{children}</dd>
    </div>
  );
}
