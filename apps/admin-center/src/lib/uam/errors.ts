import "server-only";

/**
 * UAM 调用失败的两类：
 * - `problem`：UAM 明确给出业务结论（RFC 9457 problem，带稳定 `code`），界面据此显示文案；
 * - `unavailable`：超时、连接失败、5xx、响应无法理解——UAM 当前不可用；
 */
export type UamErrorKind = "problem" | "unavailable";

export interface FieldError {
  field: string;
  reason: string;
}

/**
 * `session_invalid` 的可选补充原因（contracts/uam/openapi.yaml 的 `Problem.reason`）：
 * 仅 verify 的 401，且仅当会话被管理员强制终止（`terminated`）时才有；其余结束原因都不带。
 */
export type ProblemReason = "terminated";

/** 只认契约声明的字面量：其它取值（含类型不对）一律当作没有 reason。 */
export function parseProblemReason(raw: unknown): ProblemReason | undefined {
  return raw === "terminated" ? raw : undefined;
}

export class UamError extends Error {
  kind: UamErrorKind;
  status?: number;
  code?: string;
  reason?: ProblemReason;
  fieldErrors?: FieldError[];
  retryAfter?: number;

  constructor(kind: UamErrorKind, message: string, extra: Partial<Omit<UamError, keyof Error | "kind">> = {}) {
    super(message);
    this.name = "UamError";
    this.kind = kind;
    Object.assign(this, extra);
  }
}

export type MessageMode = "read" | "write";

const UNAVAILABLE: Record<MessageMode, string> = {
  read: "服务暂不可用，请稍后重试",
  write: "操作未生效",
};

/**
 * UAM 问题码 → 界面文案（contracts/web-routes.md §5）。
 * `validation_failed`（HTTP 422，带 `errors[]`）按 `code` 识别，不看状态码。
 * `bad_request`（HTTP 400，请求读不懂）故意不在表内：Admin Center 不会发出这样的请求，出现即视为意外拒绝，
 * 与未知问题码一样按「服务暂不可用 / 操作未生效」呈现，不当作凭据或输入错误。
 */
const PROBLEM_MESSAGES: Record<string, string | ((error: UamError) => string)> = {
  invalid_credentials: "账号名或密码错误",
  throttled: (e) => (e.retryAfter ? `尝试过于频繁，请 ${e.retryAfter} 秒后再试` : "尝试过于频繁，请稍后再试"),
  session_invalid: "会话已结束，请重新登录",
  account_name_taken: "账号名已被使用",
  validation_failed: "输入有误，请按提示修改后重试",
  cannot_disable_self: "不能停用当前登录的用户",
  cannot_disable_last_active_user: "不能停用最后一个正常用户",
  cannot_terminate_own_session: "这是你当前使用的会话，请改用「退出登录」",
  session_not_found: "该会话已结束，已刷新为最新状态",
  user_not_found: "用户不存在",
  current_password_incorrect: "当前密码不正确",
  weak_password: "新密码不满足强度要求",
  same_as_current: "新密码不能与当前密码相同",
};

/** 把 UamError 变成给用户看的文案；`mode` 只影响「不可用」类：读提示服务暂不可用，写提示操作未生效。 */
export function uamErrorMessage(error: UamError, mode: MessageMode): string {
  if (error.kind === "problem" && error.code) {
    const entry = PROBLEM_MESSAGES[error.code];
    if (entry) return typeof entry === "function" ? entry(error) : entry;
  }
  return UNAVAILABLE[mode];
}

const FIELD_REASONS: Record<string, string> = {
  required: "此项必填",
  too_long: "内容过长",
  invalid_chars: "含有不允许的字符",
  too_short: "长度不足",
  too_weak: "强度不足",
  taken: "账号名已被使用",
};

/** `validation_failed` 的字段级原因 → 字段旁的文案。 */
export function fieldReasonMessage(reason: string): string {
  return FIELD_REASONS[reason] ?? "输入不合法";
}
