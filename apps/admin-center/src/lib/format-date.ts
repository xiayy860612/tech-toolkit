const pad = (n: number) => String(n).padStart(2, "0");

/** ISO 时间 → `YYYY-MM-DD HH:mm`（运行环境的本地时区，与原型一致）；无法解析时原样返回。 */
export function formatDateTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
