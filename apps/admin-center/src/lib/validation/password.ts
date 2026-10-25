import "server-only";
import { z } from "zod";
import { fieldReasonMessage } from "@/lib/uam/errors";

/** 规则与 UAM 契约（ChangePasswordRequest）、spec FR-022 / FR-027 一致；UAM 仍会再校验一次（它是权威）。 */
const PASSWORD_MAX = 256; // 当前密码：UAM 请求 DTO 的 @Size 上限
const NEW_PASSWORD_MAX = 128; // 新密码：UAM PasswordPolicy.MAX_LENGTH，按 Unicode 码点计（代理对算一个）

const CURRENT_REQUIRED = "请填写当前密码";
const NEW_REQUIRED = "请填写新密码";
const CONFIRM_REQUIRED = "请再次输入新密码";
const PASSWORD_TOO_LONG = `密码最长 ${PASSWORD_MAX} 个字符`;
const NEW_PASSWORD_TOO_LONG = `密码最长 ${NEW_PASSWORD_MAX} 个字符`;
export const NEW_PASSWORD_RULE = "新密码至少 10 位，且同时包含字母和数字";
export const SAME_AS_CURRENT = "新密码不能与当前密码相同";
export const CURRENT_PASSWORD_INCORRECT = "当前密码不正确";
const CONFIRM_MISMATCH = "两次输入的新密码不一致";

// 密码不去首尾空白：空白也是密码的一部分
const changePasswordSchema = z.object({
  currentPassword: z.string().min(1, CURRENT_REQUIRED).max(PASSWORD_MAX, PASSWORD_TOO_LONG),
  newPassword: z
    .string()
    .min(1, NEW_REQUIRED)
    .refine((s) => Array.from(s).length <= NEW_PASSWORD_MAX, NEW_PASSWORD_TOO_LONG)
    .refine((s) => s.length >= 10 && /[A-Za-z]/.test(s) && /\d/.test(s), NEW_PASSWORD_RULE),
  confirmPassword: z.string().min(1, CONFIRM_REQUIRED),
});

export type ChangePasswordField = "currentPassword" | "newPassword" | "confirmPassword";
export type ChangePasswordFieldErrors = Partial<Record<ChangePasswordField, string>>;

const FIELDS: readonly ChangePasswordField[] = ["currentPassword", "newPassword", "confirmPassword"];
const isField = (value: unknown): value is ChangePasswordField => FIELDS.includes(value as ChangePasswordField);

/**
 * 修改密码表单经 Server Action 返回给浏览器的状态。**绝不包含任何密码**（含失败时也不回填）：三个密码框一律清空。
 * `success`：已修改（会话不受影响），界面显示「密码已修改」。
 */
export interface ChangePasswordFormState {
  /** 表单级提示：限流、服务不可用、操作未生效、请求来源校验失败等。 */
  formError?: string;
  fieldErrors?: ChangePasswordFieldErrors;
  success?: true;
}

export interface ChangePasswordInput {
  currentPassword: string;
  newPassword: string;
}

export type ParsedChangePassword =
  | { success: true; data: ChangePasswordInput }
  | { success: false; fieldErrors: ChangePasswordFieldErrors };

/** 缺失或非字符串的字段按空处理。 */
function text(formData: FormData, name: ChangePasswordField): string {
  const value = formData.get(name);
  return typeof value === "string" ? value : "";
}

/**
 * 必填、强度、长度，再加两条跨字段规则：确认项与新密码一致、新密码不同于当前密码。
 * 返回的 `data` 不含确认项（它只在 Admin Center 检查，不发给 UAM）。每个字段只给第一条原因。
 */
export function parseChangePasswordForm(formData: FormData): ParsedChangePassword {
  const raw = {
    currentPassword: text(formData, "currentPassword"),
    newPassword: text(formData, "newPassword"),
    confirmPassword: text(formData, "confirmPassword"),
  };
  const result = changePasswordSchema.safeParse(raw);

  const fieldErrors: ChangePasswordFieldErrors = {};
  if (!result.success) {
    for (const issue of result.error.issues) {
      const field = issue.path[0];
      if (isField(field) && !fieldErrors[field]) fieldErrors[field] = issue.message;
    }
  }
  if (!fieldErrors.newPassword && raw.newPassword === raw.currentPassword) fieldErrors.newPassword = SAME_AS_CURRENT;
  if (!fieldErrors.confirmPassword && raw.confirmPassword !== raw.newPassword) fieldErrors.confirmPassword = CONFIRM_MISMATCH;

  if (Object.keys(fieldErrors).length > 0) return { success: false, fieldErrors };
  return { success: true, data: { currentPassword: raw.currentPassword, newPassword: raw.newPassword } };
}

const REASONS: Record<"currentPassword" | "newPassword", Record<string, string>> = {
  currentPassword: { required: CURRENT_REQUIRED, too_long: PASSWORD_TOO_LONG },
  newPassword: { required: NEW_REQUIRED, too_short: NEW_PASSWORD_RULE, too_weak: NEW_PASSWORD_RULE, too_long: NEW_PASSWORD_TOO_LONG },
};

/** UAM `validation_failed` 的字段错误 → 字段旁文案（不认识的字段忽略，不认识的原因用通用文案；同一字段取第一条）。 */
export function mapUamPasswordFieldErrors(errors: ReadonlyArray<{ field: string; reason: string }>): ChangePasswordFieldErrors {
  const out: ChangePasswordFieldErrors = {};
  for (const { field, reason } of errors) {
    if ((field === "currentPassword" || field === "newPassword") && !out[field]) {
      out[field] = REASONS[field][reason] ?? fieldReasonMessage(reason);
    }
  }
  return out;
}
