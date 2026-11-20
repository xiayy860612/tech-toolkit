"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from "@/components/ui/dialog";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Table, TBody, Td, Th, THead, Tr } from "@/components/ui/table";
import { TempPasswordDialog } from "@/features/users/TempPasswordDialog";
import { apiRequest } from "@/lib/api/client";
import { ApiError, messageOf } from "@/lib/api/errors";
import type { Account, AccountWithTemporaryPassword } from "@/lib/api/types";
import { formatDateTime, formatTime, statusLabel } from "@/lib/format";
import { passwordSchema, usernameSchema } from "@/lib/validation";
import { ConfirmDialog } from "./ConfirmDialog";

const TYPE_LABELS: Record<Account["type"], string> = { USERNAME_PASSWORD: "用户名密码" };

type Intent =
  | { kind: "disable"; account: Account; last: true }
  | { kind: "delete"; account: Account; last: boolean }
  | { kind: "reset"; account: Account };

type State =
  { kind: "loading" } | { kind: "error"; message: string } | { kind: "ok"; accounts: Account[] };

const LAST_ACTIVE = "LAST_ACTIVE_ACCOUNT_CONFIRM_REQUIRED";

export function AccountsSection({ userId }: { userId: string }) {
  const [state, setState] = useState<State>({ kind: "loading" });
  const [actionError, setActionError] = useState<string | null>(null);
  const [intent, setIntent] = useState<Intent | null>(null);
  const [adding, setAdding] = useState(false);
  const [issued, setIssued] = useState<string | null>(null);

  const base = `/users/${userId}/accounts`;

  const load = useCallback(() => {
    apiRequest<Account[]>(base)
      .then((accounts) => setState({ kind: "ok", accounts }))
      .catch((error) => setState({ kind: "error", message: messageOf(error) }));
  }, [base]);

  useEffect(load, [load]);

  const accounts = state.kind === "ok" ? state.accounts : [];
  const activeCount = accounts.filter((a) => a.status === "ACTIVE").length;
  const isLastActive = (a: Account) => a.status === "ACTIVE" && activeCount === 1;

  /** 执行一个操作；失败只展示错误。返回是否成功。 */
  async function run(path: string, method: "POST" | "DELETE" = "POST"): Promise<boolean> {
    setActionError(null);
    try {
      const result = await apiRequest<AccountWithTemporaryPassword | undefined>(path, { method });
      load();
      if (result?.temporaryPassword) setIssued(result.temporaryPassword);
      return true;
    } catch (error) {
      if (error instanceof ApiError && error.code === LAST_ACTIVE) {
        throw error;
      }
      setActionError(messageOf(error));
      return false;
    }
  }

  async function disable(account: Account, confirm: boolean) {
    const path = `${base}/${account.id}/disable${confirm ? "?confirm=true" : ""}`;
    try {
      await run(path);
    } catch {
      setIntent({ kind: "disable", account, last: true }); // 后端要求确认：补弹确认框
    }
  }

  async function remove(account: Account, confirm: boolean) {
    const path = `${base}/${account.id}${confirm ? "?confirm=true" : ""}`;
    try {
      await run(path, "DELETE");
    } catch {
      setIntent({ kind: "delete", account, last: true });
    }
  }

  if (state.kind === "loading") {
    return (
      <div
        role="status"
        aria-label="加载中"
        className="h-32 animate-pulse rounded-card bg-table-head"
      />
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>登录账号</CardTitle>
        <Button variant="secondary" onClick={() => setAdding(true)}>
          新增 Account
        </Button>
      </CardHeader>

      {actionError && (
        <p role="alert" className="mb-4 rounded-control bg-danger-bg px-3 py-2 text-sm text-danger">
          {actionError}
        </p>
      )}

      {state.kind === "error" ? (
        <div role="alert" className="flex flex-col items-center gap-3 p-6 text-center">
          <p>{`加载失败：${state.message}`}</p>
          <Button
            variant="secondary"
            onClick={() => {
              setState({ kind: "loading" });
              load();
            }}
          >
            重试
          </Button>
        </div>
      ) : (
        <Table>
          <THead>
            <Tr>
              <Th>类型</Th>
              <Th>登录标识</Th>
              <Th>状态</Th>
              <Th>最近登录</Th>
              <Th>操作</Th>
            </Tr>
          </THead>
          <TBody>
            {accounts.map((a) => (
              <Tr key={a.id}>
                <Td>{TYPE_LABELS[a.type]}</Td>
                <Td className="font-medium">{a.identifier}</Td>
                <Td>
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={a.status === "ACTIVE" ? "success" : "danger"}>
                      {statusLabel(a.status)}
                    </Badge>
                    {a.locked && (
                      <Badge tone="warning">{`已锁定至 ${formatTime(a.lockedUntil)}`}</Badge>
                    )}
                  </div>
                </Td>
                <Td>{formatDateTime(a.lastLoginAt, "从未登录")}</Td>
                <Td>
                  <div className="flex flex-wrap items-center gap-4 text-sm">
                    {a.status === "ACTIVE" ? (
                      <ActionButton
                        onClick={() =>
                          isLastActive(a)
                            ? setIntent({ kind: "disable", account: a, last: true })
                            : void disable(a, false)
                        }
                      >
                        停用
                      </ActionButton>
                    ) : (
                      <ActionButton
                        onClick={() => void run(`${base}/${a.id}/enable`).catch(() => undefined)}
                      >
                        启用
                      </ActionButton>
                    )}
                    {a.locked && (
                      <ActionButton
                        onClick={() => void run(`${base}/${a.id}/unlock`).catch(() => undefined)}
                      >
                        解锁
                      </ActionButton>
                    )}
                    <ActionButton onClick={() => setIntent({ kind: "reset", account: a })}>
                      重置密码
                    </ActionButton>
                    <ActionButton
                      onClick={() =>
                        setIntent({ kind: "delete", account: a, last: isLastActive(a) })
                      }
                    >
                      删除
                    </ActionButton>
                  </div>
                </Td>
              </Tr>
            ))}
          </TBody>
        </Table>
      )}

      <ConfirmDialog
        open={intent?.kind === "disable"}
        onOpenChange={(open) => !open && setIntent(null)}
        title="停用该账号？"
        description="这是该用户最后一个可用账号，停用后该用户将无法登录。"
        confirmLabel="确认停用"
        onConfirm={() => (intent?.kind === "disable" ? disable(intent.account, true) : undefined)}
      />
      <ConfirmDialog
        open={intent?.kind === "delete"}
        onOpenChange={(open) => !open && setIntent(null)}
        title="删除该账号？"
        description={
          intent?.kind === "delete" && intent.last
            ? "这是该用户最后一个可用账号，删除后该用户将无法登录，且无法恢复。"
            : "删除后该账号无法恢复，其已登录状态立即失效。"
        }
        confirmLabel="确认删除"
        onConfirm={() =>
          intent?.kind === "delete" ? remove(intent.account, intent.last) : undefined
        }
      />
      <ConfirmDialog
        open={intent?.kind === "reset"}
        onOpenChange={(open) => !open && setIntent(null)}
        title="重置该账号的密码？"
        description="重置后旧密码立即失效，该账号已登录状态立即失效，下次登录须修改密码。"
        confirmLabel="确认重置"
        tone="primary"
        onConfirm={async () => {
          if (intent?.kind === "reset") {
            await run(`${base}/${intent.account.id}/reset-password`).catch(() => undefined);
          }
        }}
      />

      <AddAccountDialog
        open={adding}
        onOpenChange={setAdding}
        userId={userId}
        onCreated={(password) => {
          setAdding(false);
          load();
          if (password) setIssued(password);
        }}
      />

      {issued && <TempPasswordDialog password={issued} onClose={() => setIssued(null)} />}
    </Card>
  );
}

function ActionButton({ onClick, children }: { onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" className="text-accent hover:underline" onClick={onClick}>
      {children}
    </button>
  );
}

function AddAccountDialog({
  open,
  onOpenChange,
  userId,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  userId: string;
  onCreated: (temporaryPassword: string | null) => void;
}) {
  const [username, setUsername] = useState("");
  const [temporaryPassword, setTemporaryPassword] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitError(null);
    const found: Record<string, string> = {};
    const u = usernameSchema.safeParse(username);
    if (!u.success) found.username = u.error.issues[0]?.message ?? "用户名格式不正确";
    if (temporaryPassword) {
      const p = passwordSchema.safeParse(temporaryPassword);
      if (!p.success) found.temporaryPassword = p.error.issues[0]?.message ?? "密码不符合要求";
    }
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    setLoading(true);
    try {
      const created = await apiRequest<AccountWithTemporaryPassword>(`/users/${userId}/accounts`, {
        method: "POST",
        body: {
          type: "USERNAME_PASSWORD",
          username,
          ...(temporaryPassword ? { temporaryPassword } : {}),
        },
      });
      setUsername("");
      setTemporaryPassword("");
      onCreated(created.temporaryPassword ?? null);
    } catch (error) {
      if (error instanceof ApiError && error.code === "USERNAME_TAKEN") {
        setErrors({ username: messageOf(error) });
      } else {
        setSubmitError(messageOf(error));
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={submit} noValidate className="flex flex-col gap-4">
          <DialogTitle>新增 Account</DialogTitle>
          <DialogDescription>为该用户新增一个用户名密码登录账号。</DialogDescription>
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
          <Field label="临时密码" hint="留空则由系统生成" error={errors.temporaryPassword}>
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
          {submitError && (
            <p role="alert" className="rounded-control bg-danger-bg px-3 py-2 text-sm text-danger">
              {submitError}
            </p>
          )}
          <DialogFooter>
            <Button variant="secondary" onClick={() => onOpenChange(false)}>
              取消
            </Button>
            <Button type="submit" loading={loading}>
              创建
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
