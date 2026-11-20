import { ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

const BTN =
  "inline-flex size-11 items-center justify-center rounded-control border border-border bg-surface text-sm hover:bg-table-head disabled:cursor-not-allowed disabled:opacity-40";

/** 1 … 当前页±1 … 末页；返回 0 起的页码，`null` 表示省略号。 */
function pageWindow(page: number, pages: number): (number | null)[] {
  const keep = new Set([0, pages - 1, page - 1, page, page + 1].filter((p) => p >= 0 && p < pages));
  const out: (number | null)[] = [];
  let prev = -1;
  for (const p of [...keep].sort((a, b) => a - b)) {
    if (p - prev > 1) out.push(null);
    out.push(p);
    prev = p;
  }
  return out;
}

export function Pagination({
  page,
  size,
  total,
  onPageChange,
}: {
  page: number;
  size: number;
  total: number;
  onPageChange: (page: number) => void;
}) {
  const pages = Math.ceil(total / size);
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
      <p className="text-sm text-text-muted">{`共 ${total} 条，每页 ${size} 条`}</p>
      <div className="flex items-center gap-1.5">
        <button
          type="button"
          className={BTN}
          aria-label="上一页"
          disabled={page <= 0}
          onClick={() => onPageChange(page - 1)}
        >
          <ChevronLeft className="size-4" aria-hidden="true" />
        </button>
        {pageWindow(page, pages).map((p, i) =>
          p === null ? (
            <span key={`gap-${i}`} className="px-1 text-text-muted">
              …
            </span>
          ) : (
            <button
              key={p}
              type="button"
              aria-label={`第 ${p + 1} 页`}
              aria-current={p === page ? "page" : undefined}
              className={cn(
                BTN,
                p === page && "border-accent bg-accent text-white hover:bg-accent",
              )}
              onClick={() => onPageChange(p)}
            >
              {p + 1}
            </button>
          ),
        )}
        <button
          type="button"
          className={BTN}
          aria-label="下一页"
          disabled={page >= pages - 1}
          onClick={() => onPageChange(page + 1)}
        >
          <ChevronRight className="size-4" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
