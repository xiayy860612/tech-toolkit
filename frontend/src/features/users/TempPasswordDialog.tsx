"use client";

import { Copy } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from "@/components/ui/dialog";

/** 一次性临时密码展示框：只能由「关闭」按钮关闭，关闭后不可再次查看。 */
export function TempPasswordDialog({
  password,
  onClose,
}: {
  password: string;
  onClose: () => void;
}) {
  const [copyState, setCopyState] = useState<"idle" | "copied" | "failed">("idle");

  async function copy() {
    try {
      await navigator.clipboard.writeText(password);
      setCopyState("copied");
    } catch {
      setCopyState("failed");
    }
  }

  return (
    <Dialog open onOpenChange={() => {}}>
      <DialogContent
        onEscapeKeyDown={(e) => e.preventDefault()}
        onInteractOutside={(e) => e.preventDefault()}
      >
        <DialogTitle>一次性临时密码</DialogTitle>
        <DialogDescription>
          关闭后不可再次查看，请立即复制并安全转交给用户；用户首次登录须修改密码。
        </DialogDescription>
        <div className="mt-4 flex items-center gap-2 rounded-control border border-border bg-table-head p-3">
          <code className="flex-1 font-mono text-base break-all select-all">{password}</code>
          <Button variant="secondary" onClick={copy}>
            <Copy className="size-4" aria-hidden="true" />
            {copyState === "copied" ? "已复制" : "复制"}
          </Button>
        </div>
        {copyState === "failed" && (
          <p role="alert" className="mt-2 text-sm text-danger">
            复制失败，请手动选中后复制
          </p>
        )}
        <DialogFooter>
          <Button onClick={onClose}>我已记下，关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
