import type { InputHTMLAttributes } from "react";
import { cn } from "@/lib/utils";

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={cn(
        "min-h-11 w-full rounded-control border border-input-border bg-surface px-3 text-sm text-text placeholder:text-text-muted/70 disabled:bg-table-head aria-[invalid=true]:border-danger",
        className,
      )}
      {...props}
    />
  );
}
