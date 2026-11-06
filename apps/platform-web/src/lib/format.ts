/** Deterministic (UTC) timestamp for server-rendered tables. */
export function formatDateTime(iso: string): string {
  const time = Date.parse(iso);
  if (Number.isNaN(time)) return iso;
  return `${new Date(time).toISOString().slice(0, 16).replace('T', ' ')} UTC`;
}
