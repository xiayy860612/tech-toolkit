import type { ReactNode } from "react";
import { Card } from "@/components/ui/card";

/** 空态 / 错误态的文案块：标题 + 说明 + 可选操作。 */
export function StateMessage({
  title,
  description,
  as: Title = "strong",
  children,
}: {
  title: string;
  description?: string;
  /** 页面没有其它 h1 时用 `h1`（如「用户不存在」页）。 */
  as?: "strong" | "h1" | "h2";
  children?: ReactNode;
}) {
  return (
    <div className="px-4 py-10 text-center text-muted-foreground">
      <Title className="mb-1 block text-base font-semibold text-foreground">{title}</Title>
      {description && <p>{description}</p>}
      {children && <div className="mt-4">{children}</div>}
    </div>
  );
}

export function StateCard(props: Parameters<typeof StateMessage>[0]) {
  return (
    <Card className="gap-0 py-0">
      <StateMessage {...props} />
    </Card>
  );
}
