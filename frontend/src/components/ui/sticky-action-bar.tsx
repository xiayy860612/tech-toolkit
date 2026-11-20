import type { ReactNode } from "react";

/** 表单页吸底操作栏：左侧校验摘要，右侧操作按钮。 */
export function StickyActionBar({ summary, children }: { summary?: string; children: ReactNode }) {
  return (
    <div className="sticky bottom-0 -mx-8 mt-6 flex items-center justify-between gap-4 border-t border-border bg-surface px-8 py-3">
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
