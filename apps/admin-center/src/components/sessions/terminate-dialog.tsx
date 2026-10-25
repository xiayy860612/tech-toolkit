"use client";

import { createContext, useContext, useEffect, useId, useRef, useState, useTransition, type ReactNode } from "react";
import { terminateSession, type TerminateResult } from "@/app/(shell)/sessions/[userId]/actions";
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

/** 强制下线成功后会话卡片会随页面刷新消失，所以结果提示不能放在卡片里：由外层的 `TerminateFeedback` 承载。 */
const ResultContext = createContext<(result: TerminateResult | null) => void>(() => undefined);

/**
 * 会话页的结果提示区（包住会话列表，列表变空态时它仍在）：成功与「会话已结束」是普通提示（status），
 * 其余是失败（alert），失败绝不显示成功。成功 / 已结束时卡片随刷新消失，焦点移到提示上，不丢给 body。
 */
export function TerminateFeedback({ children }: { children: ReactNode }) {
  const [result, setResult] = useState<TerminateResult | null>(null);
  const notice = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (result && (result.ok || result.ended)) notice.current?.focus();
  }, [result]);

  return (
    <ResultContext value={setResult}>
      {result &&
        (result.ok || result.ended ? (
          <Alert ref={notice} role="status" tabIndex={-1} className="mb-3 outline-none">
            <span className="col-start-2">{result.message}</span>
          </Alert>
        ) : (
          <Alert variant="destructive" className="mb-3 border-0 bg-destructive/10">
            <span className="col-start-2">{result.message}</span>
          </Alert>
        ))}
      {children}
    </ResultContext>
  );
}

const OWN_SESSION_HINT = "这是你当前使用的会话，请改用顶栏用户菜单中的「退出登录」。";

/**
 * 会话卡片上的「强制下线」（FR-030）：二次确认，说明所属账号与后果（界面见 docs/system-design/admin-center/prototype）。
 * - `current`：操作人自己正在使用的会话，入口禁用并提示改用「退出登录」（UAM 同样以 409 拒绝）；
 * - 确认后调用 Server Action，结果交给 `TerminateFeedback`；处理中不能中途关闭（防重复提交）；
 * - 普通按钮而非 AlertDialogAction：后者点击即关闭，而这里要等动作返回后再关。
 */
export function TerminateDialog({
  userId,
  sessionRef,
  accountName,
  current,
}: {
  userId: string;
  sessionRef: string;
  accountName: string;
  current: boolean;
}) {
  const setResult = useContext(ResultContext);
  const [open, setOpen] = useState(false);
  const [pending, startTransition] = useTransition();
  // 动作成功 / 会话已结束：卡片即将消失，关闭对话框时不要把焦点还给它
  const cardGone = useRef(false);
  const hintId = useId();

  function confirm() {
    startTransition(async () => {
      setResult(null);
      const outcome = await terminateSession(userId, sessionRef);
      cardGone.current = outcome.ok || outcome.ended === true;
      setResult(outcome);
      setOpen(false);
    });
  }

  return (
    <div className="flex flex-col gap-2">
      <AlertDialog
        open={open}
        onOpenChange={(next) => {
          if (pending) return;
          setOpen(next);
        }}
      >
        <AlertDialogTrigger asChild>
          <Button
            type="button"
            variant="outline"
            className="h-11 w-fit border-destructive px-6 text-destructive hover:text-destructive"
            disabled={current}
            aria-describedby={current ? hintId : undefined}
          >
            强制下线
          </Button>
        </AlertDialogTrigger>
        {current && (
          <p id={hintId} className="text-sm text-muted-foreground">
            {OWN_SESSION_HINT}
          </p>
        )}
        <AlertDialogContent
          onCloseAutoFocus={(event) => {
            if (cardGone.current) event.preventDefault();
          }}
        >
          <AlertDialogHeader className="min-w-0">
            <AlertDialogTitle className="[overflow-wrap:anywhere]">强制下线该会话？</AlertDialogTitle>
            <AlertDialogDescription className="[overflow-wrap:anywhere]">
              {`账号「${accountName}」的这个会话将立即结束；该用户的其他会话不受影响，账号与用户状态也不会改变。对方下一次操作会被带到登录页。`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel className="h-11" disabled={pending}>
              取消
            </AlertDialogCancel>
            <Button type="button" variant="destructive" className="h-11" disabled={pending} onClick={confirm}>
              {pending ? "处理中…" : "强制下线"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
