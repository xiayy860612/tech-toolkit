import Link from "next/link";
import { Button } from "@/components/ui/button";
import { UamError, uamErrorMessage } from "@/lib/uam/errors";
import { StateCard } from "./state-card";

/**
 * 读 UAM 失败时的内容区（contracts/web-routes.md §5，读：服务暂不可用，请稍后重试）。
 * 超时 / 5xx / 密钥配置错误都不登出用户，所以只说明登录状态已保留；`retryHref` 是同一页面的地址。
 */
export function UamReadError({ error, retryHref, as }: { error: UamError; retryHref: string; as?: "strong" | "h1" }) {
  return (
    <StateCard
      title={uamErrorMessage(error, "read")}
      description={error.kind === "problem" ? undefined : "暂时无法获取数据。你的登录状态已保留。"}
      as={as}
    >
      <Button asChild variant="outline" className="h-11">
        <Link href={retryHref}>重试</Link>
      </Button>
    </StateCard>
  );
}
