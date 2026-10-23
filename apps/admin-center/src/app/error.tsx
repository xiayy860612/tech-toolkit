"use client";

import { useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

export default function ErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => {
    // 只记摘要，不记错误信息本身（可能含内部细节）
    console.error("页面渲染出错", error.digest);
  }, [error]);

  return (
    <main className="grid min-h-svh place-items-center px-4 py-6">
      <Card className="w-full max-w-md">
        <CardContent className="py-10 text-center">
          <h1 className="mb-2 text-lg font-bold">页面出了点问题</h1>
          <p className="mb-4 text-muted-foreground">请重试；如果仍然出错，请稍后再来。</p>
          <Button className="h-11" onClick={reset}>
            重试
          </Button>
        </CardContent>
      </Card>
    </main>
  );
}
