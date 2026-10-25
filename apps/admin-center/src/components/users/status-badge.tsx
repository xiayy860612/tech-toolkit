import { Badge } from "@/components/ui/badge";

const LABELS = { ACTIVE: "正常", DISABLED: "已停用", DELETED: "已删除" } as const;

export type Status = keyof typeof LABELS;

/** 状态徽标：用文字表达状态（不只靠颜色）。 */
export function StatusBadge({ status }: { status: Status }) {
  return <Badge variant={status === "ACTIVE" ? "outline" : "destructive"}>{LABELS[status]}</Badge>;
}
