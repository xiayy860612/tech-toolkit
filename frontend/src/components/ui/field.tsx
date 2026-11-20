import { AlertCircle } from "lucide-react";
import { useId, type ReactNode } from "react";

export interface FieldControlProps {
  id: string;
  required?: boolean;
  "aria-invalid"?: true;
  "aria-describedby"?: string;
}

interface FieldProps {
  label: string;
  error?: string;
  hint?: string;
  required?: boolean;
  children: (props: FieldControlProps) => ReactNode;
}

/** label + 控件 + 提示/错误；错误以 aria-invalid + aria-describedby 关联控件。 */
export function Field({ label, error, hint, required, children }: FieldProps) {
  const id = useId();
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ");

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-text">
        {label}
        {required && (
          <span className="ml-0.5 text-danger" aria-hidden="true">
            *
          </span>
        )}
      </label>
      {children({
        id,
        required,
        "aria-invalid": error ? true : undefined,
        "aria-describedby": describedBy || undefined,
      })}
      {hint && (
        <p id={hintId} className="text-xs text-text-muted">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="flex items-center gap-1 text-xs text-danger">
          <AlertCircle className="size-3.5" aria-hidden="true" />
          {error}
        </p>
      )}
    </div>
  );
}
