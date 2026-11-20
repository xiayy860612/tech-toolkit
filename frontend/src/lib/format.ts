import type { Role, Status } from "./api/types";

const pad = (n: number) => String(n).padStart(2, "0");

function parse(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** RFC 3339 → 本地时区 `yyyy-MM-dd HH:mm`；缺失或非法时返回占位。 */
export function formatDateTime(iso: string | null | undefined, placeholder = "—"): string {
  const d = parse(iso);
  if (!d) return placeholder;
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** RFC 3339 → 本地时区 `HH:mm`。 */
export function formatTime(iso: string | null | undefined, placeholder = "—"): string {
  const d = parse(iso);
  return d ? `${pad(d.getHours())}:${pad(d.getMinutes())}` : placeholder;
}

const ROLE_LABELS: Record<Role, string> = { TENANT_ADMIN: "租户管理员", MEMBER: "普通用户" };
const STATUS_LABELS: Record<Status, string> = { ACTIVE: "启用", DISABLED: "停用" };

export const roleLabel = (role: Role): string => ROLE_LABELS[role];
export const statusLabel = (status: Status): string => STATUS_LABELS[status];
