"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { apiRequest } from "@/lib/api/client";
import { messageOf } from "@/lib/api/errors";
import type { User } from "@/lib/api/types";
import { UserForm } from "./UserForm";

type State =
  | { kind: "loading" }
  | { kind: "error"; message: string }
  | { kind: "ok"; user: User; revision: number };

/** 编辑页：先取最新用户（含 version），保存冲突时可重新加载。 */
export function UserEditor({ id, currentUserId }: { id: string; currentUserId?: number }) {
  const [state, setState] = useState<State>({ kind: "loading" });

  const load = useCallback(() => {
    setState({ kind: "loading" });
    apiRequest<User>(`/users/${id}`)
      .then((user) =>
        setState((s) => ({ kind: "ok", user, revision: s.kind === "ok" ? s.revision + 1 : 0 })),
      )
      .catch((error) => setState({ kind: "error", message: messageOf(error) }));
  }, [id]);

  useEffect(load, [load]);

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
        <div className="flex gap-3">
          <Button variant="secondary" onClick={load}>
            重试
          </Button>
          <Link
            href="/users"
            className="inline-flex min-h-10 items-center text-accent hover:underline"
          >
            返回用户列表
          </Link>
        </div>
      </Card>
    );
  }
  return (
    <UserForm
      key={state.revision}
      mode="edit"
      user={state.user}
      onReload={load}
      currentUserId={currentUserId}
    />
  );
}
