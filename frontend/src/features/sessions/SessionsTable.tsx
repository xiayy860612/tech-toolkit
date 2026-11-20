"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Pagination } from "@/components/ui/pagination";
import { Table, TBody, Td, Th, THead, Tr } from "@/components/ui/table";
import { ConfirmDialog } from "@/features/accounts/ConfirmDialog";
import { apiRequest } from "@/lib/api/client";
import { messageOf } from "@/lib/api/errors";
import type { LoginSession, LoginSessionPage, Me } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";

type State =
  { kind: "loading" } | { kind: "error"; message: string } | { kind: "ok"; data: LoginSessionPage };
type Intent = { kind: "one" | "all"; session: LoginSession };

const PAGE_SIZE = 20;

export function SessionsTable() {
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(0);
  const [reloadKey, setReloadKey] = useState(0);
  const [state, setState] = useState<State>({ kind: "loading" });
  const [ownUserId, setOwnUserId] = useState<string | null>(null);
  const [intent, setIntent] = useState<Intent | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  useEffect(() => {
    apiRequest<Me>("/auth/me")
      .then((me) => setOwnUserId(me.userId))
      .catch(() => undefined); // 取不到时退而依据列表中的 current 行判断
  }, []);

  useEffect(() => {
    const ctrl = new AbortController();
    const qs = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
    if (keyword) qs.set("keyword", keyword);
    setState({ kind: "loading" });
    apiRequest<LoginSessionPage>(`/sessions?${qs}`, { signal: ctrl.signal })
      .then((data) => !ctrl.signal.aborted && setState({ kind: "ok", data }))
      .catch(
        (error) => !ctrl.signal.aborted && setState({ kind: "error", message: messageOf(error) }),
      );
    return () => ctrl.abort();
  }, [keyword, page, reloadKey]);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 4000);
    return () => clearTimeout(timer);
  }, [toast]);

  function search(event: FormEvent) {
    event.preventDefault();
    setKeyword(keywordInput.trim());
    setPage(0);
  }

  const currentRowUserId =
    state.kind === "ok" ? state.data.items.find((s) => s.current)?.userId : undefined;
  const isOwnUser = (userId: string) => userId === ownUserId || userId === currentRowUserId;

  async function confirm(active: Intent) {
    setActionError(null);
    try {
      if (active.kind === "one") {
        await apiRequest(`/sessions/${active.session.id}`, { method: "DELETE" });
        setToast("已强制下线该会话");
      } else {
        await apiRequest(`/users/${active.session.userId}/sessions`, { method: "DELETE" });
        setToast("已下线该用户的全部会话");
      }
      setReloadKey((k) => k + 1);
    } catch (error) {
      setActionError(messageOf(error));
    }
  }

  return (
    <Card className="p-0">
      <form onSubmit={search} className="flex flex-wrap items-end gap-3 p-5">
        <div className="flex min-w-60 flex-1 flex-col gap-1.5">
          <label htmlFor="session-keyword" className="text-sm font-medium">
            用户关键字
          </label>
          <Input
            id="session-keyword"
            maxLength={50}
            placeholder="搜索用户姓名或登录标识"
            value={keywordInput}
            onChange={(e) => setKeywordInput(e.target.value)}
          />
        </div>
        <Button type="submit">搜索</Button>
      </form>

      {actionError && (
        <p
          role="alert"
          className="mx-5 mb-4 rounded-control bg-danger-bg px-3 py-2 text-sm text-danger"
        >
          {actionError}
        </p>
      )}

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
            <p className="p-10 text-center text-text-muted">当前没有有效的登录会话</p>
          ) : (
            <Table>
              <THead>
                <Tr>
                  <Th>用户</Th>
                  <Th>账号</Th>
                  <Th>设备</Th>
                  <Th>IP</Th>
                  <Th>登录时间</Th>
                  <Th>最近活跃</Th>
                  <Th>到期时间</Th>
                  <Th>操作</Th>
                </Tr>
              </THead>
              <TBody>
                {state.data.items.map((s) => (
                  <Tr key={s.id}>
                    <Td className="font-medium">{s.displayName}</Td>
                    <Td>{s.accountIdentifier}</Td>
                    <Td>{s.device}</Td>
                    <Td>{s.ip}</Td>
                    <Td>{formatDateTime(s.createdAt)}</Td>
                    <Td>{formatDateTime(s.lastSeenAt)}</Td>
                    <Td>{formatDateTime(s.expiresAt)}</Td>
                    <Td>
                      {s.current ? (
                        <Badge tone="info">当前</Badge>
                      ) : (
                        <div className="flex flex-wrap items-center gap-4 text-sm">
                          <button
                            type="button"
                            className="text-accent hover:underline"
                            onClick={() => setIntent({ kind: "one", session: s })}
                          >
                            强制下线
                          </button>
                          {!isOwnUser(s.userId) && (
                            <button
                              type="button"
                              className="text-danger hover:underline"
                              onClick={() => setIntent({ kind: "all", session: s })}
                            >
                              下线该用户全部会话
                            </button>
                          )}
                        </div>
                      )}
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
        open={intent !== null}
        onOpenChange={(open) => !open && setIntent(null)}
        title={intent?.kind === "all" ? "下线该用户的全部会话？" : "强制下线该会话？"}
        description={
          intent?.kind === "all"
            ? `将立即下线 ${intent.session.displayName} 的所有登录，其所有设备上都需要重新登录。`
            : intent
              ? `将立即下线 ${intent.session.device} 上的这次登录，对方下一次请求即需重新登录。`
              : ""
        }
        confirmLabel="确认下线"
        onConfirm={() => (intent ? confirm(intent) : undefined)}
      />

      {toast && (
        <p
          role="status"
          className="fixed right-6 bottom-6 z-50 rounded-control bg-sidebar px-4 py-3 text-sm text-white shadow-lg"
        >
          {toast}
        </p>
      )}
    </Card>
  );
}
