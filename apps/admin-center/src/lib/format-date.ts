const pad = (n: number) => String(n).padStart(2, "0");

/** ISO 时间 → `YYYY-MM-DD HH:mm`（运行环境的本地时区，与原型一致）；无法解析时原样返回。 */
export function formatDateTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 相对当前时间的文案（与原型一致）：刚刚 / N 分钟前 / N 小时前 / N 天前；晚于当前时间按「刚刚」；无法解析时原样返回。 */
export function formatRelative(iso: string, now: number): string {
  const time = new Date(iso).getTime();
  if (Number.isNaN(time)) return iso;
  const minutes = Math.floor((now - time) / 60_000);
  if (minutes < 1) return "刚刚";
  if (minutes < 60) return `${minutes} 分钟前`;
  if (minutes < 1440) return `${Math.floor(minutes / 60)} 小时前`;
  return `${Math.floor(minutes / 1440)} 天前`;
}
