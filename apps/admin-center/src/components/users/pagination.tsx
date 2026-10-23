import Link from "next/link";
import { Button } from "@/components/ui/button";

function PagerButton({ href, children }: { href: string | null; children: string }) {
  if (!href) {
    return (
      <span
        aria-disabled="true"
        className="inline-flex h-11 items-center justify-center rounded-md border px-4 text-sm font-medium opacity-50"
      >
        {children}
      </span>
    );
  }
  return (
    <Button asChild variant="outline" className="h-11 px-4">
      <Link href={href}>{children}</Link>
    </Button>
  );
}

/** 列表底部的分页条：摘要文案 + 上一页 / 下一页（`null` 表示该方向不可用）。用户列表与在线用户列表共用。 */
export function Pagination({ summary, prevHref, nextHref }: { summary: string; prevHref: string | null; nextHref: string | null }) {
  return (
    <nav aria-label="分页" className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-t px-4 py-3">
      <span className="text-sm text-muted-foreground">{summary}</span>
      <span className="flex gap-2">
        <PagerButton href={prevHref}>上一页</PagerButton>
        <PagerButton href={nextHref}>下一页</PagerButton>
      </span>
    </nav>
  );
}
