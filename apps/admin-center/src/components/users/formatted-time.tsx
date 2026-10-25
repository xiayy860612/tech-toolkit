import { formatDateTime } from "@/lib/format-date";

/** 语义化时间：可见文本为本地化的 `YYYY-MM-DD HH:mm`，机器可读值在 `dateTime`。 */
export function FormattedTime({ iso }: { iso: string }) {
  return <time dateTime={iso}>{formatDateTime(iso)}</time>;
}
