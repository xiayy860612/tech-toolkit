"use client";

import { useState, useTransition } from "react";
import { disableUser, enableUser, type UserStatusResult } from "@/app/(shell)/users/[userId]/actions";
import { Alert } from "@/components/ui/alert";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";

type Mode = "disable" | "enable";

/** 确认框文案（界面见 specs/002-admin-center-uam/prototype）：说明后果；用户名按文本渲染。 */
const COPY = {
  disable: {
    trigger: "停用用户",
    confirm: "停用",
    title: (name: string) => `停用用户「${name}」？`,
    body: "停用后，该用户名下的所有账号都无法登录，其所有在线会话会立即结束。之后可以重新启用，但已结束的会话不会恢复。",
  },
  enable: {
    trigger: "启用用户",
    confirm: "启用",
    title: (name: string) => `启用用户「${name}」？`,
    body: "启用后，该用户的账号可以重新登录。此前已结束的会话不会恢复。",
  },
} as const;

const SELF_REASON = "不能停用当前登录的用户。";

/**
 * 用户详情的「状态管理」区块（FR-024、FR-025）：停用 / 启用都要二次确认。
 * - `isSelf`：目标是当前登录运维人员所属的用户时，「停用用户」入口禁用并说明原因（UAM 同样拒绝）；
 *   「最后一个正常用户」客户端无从预知，交给 UAM 的 409 并显示它的文案，不在这里自行计数；
 * - 确认后调用 Server Action；结果以 status（成功）或 alert（失败，含「操作未生效」）显示，失败绝不显示成功；
 * - 同一个按钮元素随状态切换文案，焦点关闭对话框后自然回到它。
 */
export function StatusActions({
  userId,
  userName,
  status,
  isSelf,
}: {
  userId: string;
  userName: string;
  status: "ACTIVE" | "DISABLED";
  isSelf: boolean;
}) {
  const defaultMode: Mode = status === "ACTIVE" ? "disable" : "enable";
  const [mode, setMode] = useState<Mode | null>(null);
  // 对话框内容按「打开时」的操作固定：动作返回后页面状态先变、对话框后淡出，期间内容不能跟着变
  const [openedAs, setOpenedAs] = useState<Mode | null>(null);
  const [result, setResult] = useState<UserStatusResult | null>(null);
  const [pending, startTransition] = useTransition();

  const shown = COPY[openedAs ?? defaultMode];
  const blocked = defaultMode === "disable" && isSelf;

  function confirm() {
    const run = mode === "enable" ? enableUser : disableUser;
    startTransition(async () => {
      setResult(null);
      const outcome = await run(userId);
      setResult(outcome);
      setMode(null);
    });
  }

  return (
    <Card className="gap-3 p-4" role="region" aria-labelledby="status-heading">
      <h2 id="status-heading" className="text-base font-semibold">
        状态管理
      </h2>
      {result &&
        (result.ok ? (
          <Alert role="status">
            <span className="col-start-2">{result.message}</span>
          </Alert>
        ) : (
          <Alert variant="destructive" className="border-0 bg-destructive/10">
            <span className="col-start-2">{result.message}</span>
          </Alert>
        ))}
      <AlertDialog
        open={mode !== null}
        onOpenChange={(open) => {
          if (pending) return; // 处理中不允许中途关闭
          setMode(open ? defaultMode : null);
          if (open) setOpenedAs(defaultMode);
        }}
      >
        <AlertDialogTrigger asChild>
          <Button
            type="button"
            variant={defaultMode === "disable" ? "outline" : "default"}
            className={defaultMode === "disable" ? "h-11 w-fit border-destructive px-6 text-destructive hover:text-destructive" : "h-11 w-fit px-6"}
            disabled={blocked}
            aria-describedby={blocked ? "disable-reason" : undefined}
          >
            {COPY[defaultMode].trigger}
          </Button>
        </AlertDialogTrigger>
        {blocked && (
          <p id="disable-reason" className="text-sm text-muted-foreground">
            {SELF_REASON}
          </p>
        )}
        <AlertDialogContent>
          <AlertDialogHeader className="min-w-0">
            <AlertDialogTitle className="[overflow-wrap:anywhere]">{shown.title(userName)}</AlertDialogTitle>
            <AlertDialogDescription className="[overflow-wrap:anywhere]">{shown.body}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel className="h-11" disabled={pending}>
              取消
            </AlertDialogCancel>
            {/* 普通按钮而非 AlertDialogAction：后者点击即关闭，而这里要等动作返回后再关 */}
            <Button
              type="button"
              variant={mode === "enable" ? "default" : "destructive"}
              className="h-11"
              disabled={pending}
              onClick={confirm}
            >
              {pending ? "处理中…" : shown.confirm}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}
