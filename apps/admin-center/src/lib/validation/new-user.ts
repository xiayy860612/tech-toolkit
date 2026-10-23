import "server-only";
import { z } from "zod";
import { fieldReasonMessage } from "@/lib/uam/errors";

/** 规则与 UAM 契约（CreateUserRequest）、spec FR-022 一致；UAM 仍会再校验一次（它是权威）。 */
const USER_NAME_MAX = 64;
const PASSWORD_MAX = 256;
const ACCOUNT_NAME = /^[A-Za-z0-9_.-]{3,32}$/;

const USER_NAME_REQUIRED = "请填写用户名";
const USER_NAME_TOO_LONG = `用户名最长 ${USER_NAME_MAX} 个字符`;
const ACCOUNT_NAME_REQUIRED = "请填写账号名";
const ACCOUNT_NAME_RULE = "账号名为 3–32 个字母、数字或 _ . -";
const ACCOUNT_NAME_TAKEN = "账号名已被使用";
const PASSWORD_REQUIRED = "请填写初始密码";
const PASSWORD_RULE = "密码至少 10 位，且同时包含字母和数字";
const PASSWORD_TOO_LONG = `密码最长 ${PASSWORD_MAX} 个字符`;

const createUserSchema = z.object({
  // 长度按字符数（代理对算一个），与 UAM 一致
  userName: z.string().trim().min(1, USER_NAME_REQUIRED).refine((s) => Array.from(s).length <= USER_NAME_MAX, USER_NAME_TOO_LONG),
  accountName: z.string().trim().min(1, ACCOUNT_NAME_REQUIRED).regex(ACCOUNT_NAME, ACCOUNT_NAME_RULE),
  password: z
    .string()
    .min(1, PASSWORD_REQUIRED)
    .max(PASSWORD_MAX, PASSWORD_TOO_LONG)
    .refine((s) => s.length >= 10 && /[A-Za-z]/.test(s) && /\d/.test(s), PASSWORD_RULE),
});

export type CreateUserInput = z.infer<typeof createUserSchema>;
export type NewUserField = "userName" | "accountName" | "password";
export type NewUserFieldErrors = Partial<Record<NewUserField, string>>;

const FIELDS: readonly NewUserField[] = ["userName", "accountName", "password"];
const isField = (value: unknown): value is NewUserField => FIELDS.includes(value as NewUserField);

/** 失败后回显给浏览器的已填内容：**没有密码**。 */
export interface NewUserValues {
  userName: string;
  accountName: string;
}

/**
 * 新建用户表单经 Server Action 返回给浏览器的状态。**绝不包含密码**：失败后密码框一律清空。
 * `idempotencyKey`：下一次提交应带的幂等键——UAM 已明确拒绝（409 / 422）时换新键，结果未知（不可用）或请求没发出去时保持原键。
 */
export interface NewUserFormState {
  formError?: string;
  fieldErrors?: NewUserFieldErrors;
  values?: NewUserValues;
  idempotencyKey?: string;
}

export type ParsedNewUser =
  | { success: true; data: CreateUserInput }
  | { success: false; fieldErrors: NewUserFieldErrors };

/** 缺失或非字符串的字段按空处理。 */
function text(formData: FormData, name: string): string {
  const value = formData.get(name);
  return typeof value === "string" ? value : "";
}

/** 表单里已填的用户名与账号名（去首尾空白）。 */
export function readNewUserValues(formData: FormData): NewUserValues {
  return { userName: text(formData, "userName").trim(), accountName: text(formData, "accountName").trim() };
}

/** 幂等键：UAM 契约要求 16–64 个字符；只接受字母、数字与连字符（拒绝空白与控制字符，防头注入）。 */
export function readIdempotencyKey(formData: FormData): string | null {
  const key = text(formData, "idempotencyKey");
  return /^[A-Za-z0-9-]{16,64}$/.test(key) ? key : null;
}

export function parseNewUserForm(formData: FormData): ParsedNewUser {
  const result = createUserSchema.safeParse({
    userName: text(formData, "userName"),
    accountName: text(formData, "accountName"),
    password: text(formData, "password"),
  });
  if (result.success) return { success: true, data: result.data };

  const fieldErrors: NewUserFieldErrors = {};
  for (const issue of result.error.issues) {
    const field = issue.path[0];
    if (isField(field) && !fieldErrors[field]) fieldErrors[field] = issue.message;
  }
  return { success: false, fieldErrors };
}

const REASONS: Record<NewUserField, Record<string, string>> = {
  userName: { required: USER_NAME_REQUIRED, too_long: USER_NAME_TOO_LONG },
  accountName: {
    required: ACCOUNT_NAME_REQUIRED,
    too_short: ACCOUNT_NAME_RULE,
    too_long: ACCOUNT_NAME_RULE,
    invalid_chars: ACCOUNT_NAME_RULE,
    taken: ACCOUNT_NAME_TAKEN,
  },
  password: { required: PASSWORD_REQUIRED, too_short: PASSWORD_RULE, too_weak: PASSWORD_RULE },
};

/** UAM `validation_failed` 的字段错误 → 字段旁文案（不认识的字段忽略，不认识的原因用通用文案）。 */
export function mapUamFieldErrors(errors: ReadonlyArray<{ field: string; reason: string }>): NewUserFieldErrors {
  const out: NewUserFieldErrors = {};
  for (const { field, reason } of errors) {
    if (isField(field) && !out[field]) out[field] = REASONS[field][reason] ?? fieldReasonMessage(reason);
  }
  return out;
}

export const ACCOUNT_NAME_TAKEN_MESSAGE = ACCOUNT_NAME_TAKEN;
