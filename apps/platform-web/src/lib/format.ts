/** Placeholder for an empty value (e.g. a User without an Account has no username). */
export const EMPTY_VALUE = '—';

/** Fixed Chinese rule shown for PASSWORD_POLICY; the backend's English text is never displayed. */
export const PASSWORD_POLICY_MESSAGE = '新密码至少 12 位，不超过 128 位，且不得与用户名相同';

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '正常',
  SUSPENDED: '已暂停',
  DISABLED: '已停用',
  SUBSCRIBED: '已订阅',
};

export function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status;
}

export function formatUsernames(usernames: string[]): string {
  return usernames.length === 0 ? EMPTY_VALUE : usernames.join(', ');
}

/** Deterministic (UTC) timestamp for server-rendered output. */
export function formatDateTime(iso: string): string {
  const time = Date.parse(iso);
  if (Number.isNaN(time)) return iso;
  return `${new Date(time).toISOString().slice(0, 16).replace('T', ' ')} UTC`;
}

/** Timestamp in the given IANA time zone (default: the runtime's, i.e. the browser's). */
export function formatLocalDateTime(iso: string, timeZone?: string): string {
  const time = Date.parse(iso);
  if (Number.isNaN(time)) return iso;
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(time));
  const get = (type: string) => parts.find((part) => part.type === type)?.value ?? '';
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')}`;
}
