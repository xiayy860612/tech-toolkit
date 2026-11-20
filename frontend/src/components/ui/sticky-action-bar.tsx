import type { ReactNode } from "react";

/** 表单页吸底操作栏：左侧校验摘要，右侧操作按钮。 */
export function StickyActionBar({ summary, children }: { summary?: string; children: ReactNode }) {
  return (
    <div className="sticky bottom-0 -mx-4 mt-6 flex flex-wrap items-center justify-between gap-4 border-t border-border bg-surface px-4 py-3 sm:-mx-8 sm:px-8">
      <div>
        {summary && (
          <p role="status" className="text-sm text-danger">
            {summary}
          </p>
        )}
      </div>
      <div className="flex items-center gap-3">{children}</div>
    </div>
  );
}
