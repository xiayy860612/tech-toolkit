export interface FieldError {
  field: string;
  message: string;
}

/** 浏览器端统一的 API 错误：`code` 为后端/BFF 的稳定机器码（problem+json）。 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly fieldErrors: FieldError[] = [],
    message?: string,
  ) {
    super(message ?? code);
    this.name = "ApiError";
  }
}

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: "用户名或密码错误",
  UNAUTHORIZED: "登录已失效，请重新登录",
  FORBIDDEN: "没有权限执行此操作",
  PASSWORD_CHANGE_REQUIRED: "请先修改密码",
  USERNAME_TAKEN: "该用户名已被使用",
  VERSION_CONFLICT: "数据已被他人修改，请刷新后重试",
  LAST_ADMIN: "不能停用或降级最后一名管理员",
  CANNOT_DISABLE_SELF: "不能停用自己",
  LAST_ACTIVE_ACCOUNT_CONFIRM_REQUIRED: "这是该用户最后一个可用账号，请确认后继续",
  CANNOT_REVOKE_CURRENT_SESSION: "不能下线当前会话",
  CANNOT_REVOKE_OWN_SESSIONS: "不能下线自己的全部会话",
  VALIDATION_FAILED: "提交的内容有误，请检查后重试",
  NOT_FOUND: "资源不存在",
  TOO_MANY_REQUESTS: "操作过于频繁，请稍后再试",
  CROSS_SITE_REQUEST: "请求来源不被允许",
  BACKEND_UNAVAILABLE: "服务暂时不可用，请稍后重试",
  NETWORK_ERROR: "网络异常，请检查连接后重试",
};

export const GENERIC_ERROR = "操作失败，请稍后重试";

/** 把服务端逐字段错误（如 `account.username`）收成 { username: message }，按最后一段字段名匹配表单字段。 */
export function fieldErrorMap(error: ApiError): Record<string, string> {
  const map: Record<string, string> = {};
  for (const { field, message } of error.fieldErrors) {
    map[field.split(".").pop() ?? field] ??= message;
  }
  return map;
}

/** 任意异常 → 用户可读文案。 */
export function messageOf(error: unknown): string {
  return errorMessage(error instanceof ApiError ? error.code : undefined);
}

/** 按错误码映射用户可读文案。 */
export function errorMessage(code: string | undefined): string {
  return (code && MESSAGES[code]) || GENERIC_ERROR;
}
