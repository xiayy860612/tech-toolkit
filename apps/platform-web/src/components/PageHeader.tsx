import type { ReactNode } from 'react';

/** Page title on the left, an optional actions slot on the right (empty while the console is read-only). */
export function PageHeader({ title, actions }: { title: string; actions?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <h1 className="text-2xl font-semibold">{title}</h1>
      {actions ? (
        <div data-slot="page-header-actions" className="flex items-center gap-2">
          {actions}
        </div>
      ) : null}
    </div>
  );
}
