"use client";

import { useState } from "react";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { Badge } from "@/components/ui/badge";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TBody, Td, Th, THead, Tr } from "@/components/ui/table";
import { TempPasswordDialog } from "@/features/users/TempPasswordDialog";
import { apiRequest } from "@/lib/api/client";
import { messageOf } from "@/lib/api/errors";
import type { User, UserWithTemporaryPassword } from "@/lib/api/types";
import { formatDateTime, formatTime, statusLabel } from "@/lib/format";

/**
 * 用户详情页的「登录账号」区：用户名密码内置于 User（不是 Account），只提供「解锁」与「重置密码」。
 * 日后新增手机号、微信、OAuth2.0 等 Account 类型时，再在这里增加对应的列表与关联入口。
 */
export function CredentialSection({
  user,
  onChanged,
}: {
  user: User;
  onChanged: (user: User) => void;
}) {
  const [actionError, setActionError] = useState<string | null>(null);
  const [resetting, setResetting] = useState(false);
  const [issued, setIssued] = useState<string | null>(null);

  async function unlock() {
    setActionError(null);
    try {
      onChanged(await apiRequest<User>(`/users/${user.id}/unlock`, { method: "POST" }));
    } catch (error) {
      setActionError(messageOf(error));
    }
  }

  async function reset() {
    setActionError(null);
    try {
      const result = await apiRequest<UserWithTemporaryPassword>(
        `/users/${user.id}/reset-password`,
        { method: "POST" },
      );
      onChanged(result.user);
      if (result.temporaryPassword) setIssued(result.temporaryPassword);
    } catch (error) {
      setActionError(messageOf(error));
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>登录账号</CardTitle>
      </CardHeader>

      {actionError && (
        <p role="alert" className="mb-4 rounded-control bg-danger-bg px-3 py-2 text-sm text-danger">
          {actionError}
        </p>
      )}

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
          <Tr>
            <Td>用户名密码</Td>
            <Td className="font-medium">{user.username}</Td>
            <Td>
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={user.status === "ACTIVE" ? "success" : "danger"}>
                  {statusLabel(user.status)}
                </Badge>
                {user.locked && (
                  <Badge tone="warning">{`已锁定至 ${formatTime(user.lockedUntil)}`}</Badge>
                )}
              </div>
            </Td>
            <Td>{formatDateTime(user.lastLoginAt, "从未登录")}</Td>
            <Td>
              <div className="flex flex-wrap items-center gap-4 text-sm">
                {user.locked && (
                  <button
                    type="button"
                    className="text-accent hover:underline"
                    onClick={() => void unlock()}
                  >
                    解锁
                  </button>
                )}
                <button
                  type="button"
                  className="text-accent hover:underline"
                  onClick={() => setResetting(true)}
                >
                  重置密码
                </button>
              </div>
            </Td>
          </Tr>
        </TBody>
      </Table>

      <ConfirmDialog
        open={resetting}
        onOpenChange={setResetting}
        title="重置该用户的密码？"
        description="重置后旧密码立即失效，该用户已登录状态立即失效，下次登录须修改密码。"
        confirmLabel="确认重置"
        tone="primary"
        onConfirm={reset}
      />

      {issued && <TempPasswordDialog password={issued} onClose={() => setIssued(null)} />}
    </Card>
  );
}
