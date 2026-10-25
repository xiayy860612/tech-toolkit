import "server-only";

/**
 * 运维告警日志（FR-008、FR-043）：UAM 拒绝了 Admin Center 本身——401 `client_unauthenticated`（服务密钥缺失 / 无效 / 已撤销）
 * 或 403 `client_forbidden`（客户端没有该操作的权限）。用户侧按 `unverified` 处理（不登出、不清 cookie），
 * 靠这条告警让运维发现并修正配置。这里是这条告警的唯一出口。
 *
 * 只写固定字段：事件名、UAM 错误码、HTTP 状态、泛化后的操作、trace id、时间戳。
 * **绝不**写服务密钥、会话标识、cookie、密码、请求头、请求体或错误对象。
 */

export const UAM_MISCONFIGURED_EVENT = "admin-center.uam.misconfigured";

/** 同一（错误码，操作）在这个窗口内只写一条，其余只计数。 */
const WINDOW_MS = 60_000;
/** 限流表的容量上限（键由错误码 × 操作决定，本来就很少；上限只是防御）。 */
const MAX_KEYS = 256;

export interface UamMisconfiguredContext {
  method: string;
  /** UAM 请求路径（`/v1/...`，可含路径参数的真实值，写日志前会泛化为 `:id`）。 */
  path: string;
  status: number;
  /** UAM problem 里的 `code`；缺失或不合法时按状态推断。 */
  code?: string;
  traceparent?: string;
}

/** 路径里允许原样出现的固定片段（契约里的字面量）；其余一律当作标识符泛化成 `:id`。 */
const LITERAL_SEGMENTS = new Set(["v1", "auth", "sessions", "verify", "login", "logout", "users", "online-users", "accounts", "disable", "enable", "password"]);

function operationLabel(method: string, path: string): string {
  const segments = path
    .split("?")[0]
    .split("/")
    .filter(Boolean)
    .map((segment) => (LITERAL_SEGMENTS.has(segment) ? segment : ":id"));
  return `${method.toUpperCase()} /${segments.join("/")}`;
}

function errorCode(status: number, code: string | undefined): string {
  if (code && /^[a-z][a-z0-9_]{0,63}$/.test(code)) return code;
  return status === 403 ? "client_forbidden" : "client_unauthenticated";
}

function traceId(traceparent: string | undefined): string | undefined {
  const match = traceparent ? /^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$/.exec(traceparent) : null;
  return match && !/^0+$/.test(match[1]) ? match[1] : undefined;
}

interface ThrottleEntry {
  lastLoggedAt: number;
  suppressed: number;
}

const throttle = new Map<string, ThrottleEntry>();

/** 清空限流状态（测试用）。 */
export function resetUamMisconfiguredThrottle(): void {
  throttle.clear();
}

/**
 * 写一条「UAM 拒绝了 Admin Center」的告警。限流：同一（错误码，操作）每 60 秒至多一条；
 * 窗口过后的第一条带 `suppressed`（窗口内被抑制的次数），所以洪水式请求不会让日志无限增长，也不丢失规模信息。
 */
export function logUamMisconfigured(context: UamMisconfiguredContext): void {
  const code = errorCode(context.status, context.code);
  const operation = operationLabel(context.method, context.path);
  const key = `${code} ${operation}`;
  const now = Date.now();

  const entry = throttle.get(key);
  if (entry && now - entry.lastLoggedAt < WINDOW_MS) {
    entry.suppressed += 1;
    return;
  }

  const suppressed = entry?.suppressed ?? 0;
  throttle.delete(key); // 重新插入以保持「最久未写」的在最前
  if (throttle.size >= MAX_KEYS) throttle.delete(throttle.keys().next().value as string);
  throttle.set(key, { lastLoggedAt: now, suppressed: 0 });

  const trace = traceId(context.traceparent);
  console.error(
    JSON.stringify({
      level: "error",
      event: UAM_MISCONFIGURED_EVENT,
      time: new Date(now).toISOString(),
      code,
      status: context.status,
      operation,
      ...(trace ? { traceId: trace } : {}),
      ...(suppressed > 0 ? { suppressed } : {}),
    }),
  );
}
