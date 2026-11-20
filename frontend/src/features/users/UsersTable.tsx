"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Pagination } from "@/components/ui/pagination";
import { Table, TBody, Td, Th, THead, Tr } from "@/components/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ConfirmDialog } from "@/features/accounts/ConfirmDialog";
import { apiRequest } from "@/lib/api/client";
import { messageOf } from "@/lib/api/errors";
import type { User, UserPage } from "@/lib/api/types";
import { formatDateTime, roleLabel, statusLabel } from "@/lib/format";

type StatusFilter = "all" | "ACTIVE" | "DISABLED";
type State =
  { kind: "loading" } | { kind: "error"; message: string } | { kind: "ok"; data: UserPage };

const PAGE_SIZE = 20;

export function UsersTable() {
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState<StatusFilter>("all");
  const [page, setPage] = useState(0);
  const [reloadKey, setReloadKey] = useState(0);
  const [state, setState] = useState<State>({ kind: "loading" });
  const [actionError, setActionError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<User | null>(null);

  useEffect(() => {
    const ctrl = new AbortController();
    const qs = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
    if (keyword) qs.set("keyword", keyword);
    if (status !== "all") qs.set("status", status);
    setState({ kind: "loading" });
    apiRequest<UserPage>(`/users?${qs}`, { signal: ctrl.signal })
      .then((data) => !ctrl.signal.aborted && setState({ kind: "ok", data }))
      .catch(
        (error) => !ctrl.signal.aborted && setState({ kind: "error", message: messageOf(error) }),
      );
    return () => ctrl.abort();
  }, [keyword, status, page, reloadKey]);

  function search(event: FormEvent) {
    event.preventDefault();
    setKeyword(keywordInput.trim());
    setPage(0);
  }

  function changeStatus(next: StatusFilter) {
    setStatus(next);
    setPage(0);
  }

  async function setUserStatus(user: User, action: "disable" | "enable") {
    setActionError(null);
    try {
      await apiRequest(`/users/${user.id}/${action}`, { method: "POST" });
      setReloadKey((k) => k + 1);
    } catch (error) {
      setActionError(messageOf(error));
    }
  }

  return (
    <Card className="p-0">
      <div className="flex flex-col gap-4 p-5">
        <form onSubmit={search} className="flex flex-wrap items-end gap-3">
          <div className="flex min-w-60 flex-1 flex-col gap-1.5">
            <label htmlFor="user-keyword" className="text-sm font-medium">
              关键词
            </label>
            <Input
              id="user-keyword"
              maxLength={50}
              placeholder="搜索姓名或用户名"
              value={keywordInput}
              onChange={(e) => setKeywordInput(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="user-status" className="text-sm font-medium">
              状态
            </label>
            <select
              id="user-status"
              className="min-h-11 rounded-control border border-input-border bg-surface px-3 text-sm"
              value={status}
              onChange={(e) => changeStatus(e.target.value as StatusFilter)}
            >
              <option value="all">全部</option>
              <option value="ACTIVE">启用</option>
              <option value="DISABLED">停用</option>
            </select>
          </div>
          <Button type="submit">搜索</Button>
        </form>
        <Tabs value={status} onValueChange={(v) => changeStatus(v as StatusFilter)}>
          <TabsList>
            <TabsTrigger value="all">全部</TabsTrigger>
            <TabsTrigger value="ACTIVE">启用</TabsTrigger>
            <TabsTrigger value="DISABLED">停用</TabsTrigger>
          </TabsList>
        </Tabs>
        {actionError && (
          <p role="alert" className="rounded-control bg-danger-bg px-3 py-2 text-sm text-danger">
            {actionError}
          </p>
        )}
      </div>

      {state.kind === "loading" && (
        <div role="status" aria-label="加载中" className="flex flex-col gap-3 p-5">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-10 animate-pulse rounded-control bg-table-head" />
          ))}
        </div>
      )}

      {state.kind === "error" && (
        <div role="alert" className="flex flex-col items-center gap-3 p-10 text-center">
          <p>{`加载失败：${state.message}`}</p>
          <Button variant="secondary" onClick={() => setReloadKey((k) => k + 1)}>
            重试
          </Button>
        </div>
      )}

      {state.kind === "ok" && (
        <>
          {state.data.items.length === 0 ? (
            <p className="p-10 text-center text-text-muted">没有符合条件的用户</p>
          ) : (
            <Table>
              <THead>
                <Tr>
                  <Th>姓名</Th>
                  <Th>主用户名</Th>
                  <Th>角色</Th>
                  <Th>状态</Th>
                  <Th>最近登录</Th>
                  <Th>操作</Th>
                </Tr>
              </THead>
              <TBody>
                {state.data.items.map((u) => (
                  <Tr key={u.id}>
                    <Td className="font-medium">{u.displayName}</Td>
                    <Td>
                      <span>{u.primaryUsername ?? "—"}</span>
                      {u.accountCount > 1 && (
                        <span className="ml-2 text-xs text-text-muted">{`+${u.accountCount - 1} 个账号`}</span>
                      )}
                    </Td>
                    <Td>{roleLabel(u.role)}</Td>
                    <Td>
                      <Badge tone={u.status === "ACTIVE" ? "success" : "danger"}>
                        {statusLabel(u.status)}
                      </Badge>
                    </Td>
                    <Td>{formatDateTime(u.lastLoginAt, "从未登录")}</Td>
                    <Td>
                      <div className="flex items-center gap-4 text-sm">
                        <Link href={`/users/${u.id}`} className="text-accent hover:underline">
                          详情
                        </Link>
                        <Link href={`/users/${u.id}/edit`} className="text-accent hover:underline">
                          编辑
                        </Link>
                        <button
                          type="button"
                          className="text-accent hover:underline"
                          onClick={() =>
                            u.status === "ACTIVE"
                              ? setConfirming(u)
                              : void setUserStatus(u, "enable")
                          }
                        >
                          {u.status === "ACTIVE" ? "停用" : "启用"}
                        </button>
                      </div>
                    </Td>
                  </Tr>
                ))}
              </TBody>
            </Table>
          )}
          <Pagination
            page={state.data.page}
            size={state.data.size}
            total={state.data.total}
            onPageChange={setPage}
          />
        </>
      )}

      <ConfirmDialog
        open={confirming !== null}
        onOpenChange={(open) => !open && setConfirming(null)}
        title={confirming ? `停用 ${confirming.displayName}？` : "停用用户"}
        description="停用后该用户的所有账号将无法登录，已登录状态立即失效。"
        confirmLabel="确认停用"
        onConfirm={() => (confirming ? setUserStatus(confirming, "disable") : undefined)}
      />
    </Card>
  );
}
