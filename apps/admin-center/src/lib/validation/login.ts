import "server-only";
import { z } from "zod";

/** 长度上限与 UAM 契约（LoginRequest）一致。 */
const loginSchema = z.object({
  accountName: z.string().trim().min(1, "请填写账号名").max(64, "账号名最长 64 个字符"),
  password: z.string().min(1, "请填写密码").max(256, "密码最长 256 个字符"),
});

export type LoginInput = z.infer<typeof loginSchema>;

export interface LoginFieldErrors {
  accountName?: string;
  password?: string;
}

/**
 * 登录表单经 Server Action 返回给浏览器的状态。
 * **绝不包含密码**：失败后密码框一律清空；账号名可以保留，方便修改后重试。
 */
export interface LoginFormState {
  /** 表单级提示：统一的失败原因、限流、服务不可用。 */
  formError?: string;
  fieldErrors?: LoginFieldErrors;
  /** 已填的账号名（去首尾空白后）。 */
  accountName?: string;
}

export type ParsedLogin =
  | { success: true; data: LoginInput }
  | { success: false; accountName: string; fieldErrors: LoginFieldErrors };

/** 缺失或非字符串的字段按空处理。 */
export function parseLoginForm(formData: FormData): ParsedLogin {
  const text = (name: string) => {
    const value = formData.get(name);
    return typeof value === "string" ? value : "";
  };
  const raw = { accountName: text("accountName"), password: text("password") };
  const result = loginSchema.safeParse(raw);
  if (result.success) return { success: true, data: result.data };

  const fieldErrors: LoginFieldErrors = {};
  for (const issue of result.error.issues) {
    const field = issue.path[0];
    if ((field === "accountName" || field === "password") && !fieldErrors[field]) fieldErrors[field] = issue.message;
  }
  return { success: false, accountName: raw.accountName.trim(), fieldErrors };
}
