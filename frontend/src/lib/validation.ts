import { z } from "zod";

/** 校验规则与后端 / openapi.yaml 保持一致（见 contracts/openapi.yaml 与 tasks T034）。 */
export const usernameSchema = z
  .string()
  .regex(
    /^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$/,
    "用户名需 3–32 位，以字母或数字开头，仅含字母、数字、. _ -",
  );

export const phoneSchema = z.string().regex(/^1[3-9]\d{9}$/, "请输入 11 位大陆手机号");

export const emailSchema = z.email("邮箱格式不正确");

export const displayNameSchema = z
  .string()
  .trim()
  .min(1, "请输入姓名")
  .max(50, "姓名最多 50 个字符");

export interface PasswordRule {
  id: "length" | "classes";
  label: string;
  passed: boolean;
}

/** 长度 ≥ 10，且含大写 / 小写 / 数字 / 符号中至少 3 类。 */
export function checkPassword(password: string): { ok: boolean; rules: PasswordRule[] } {
  const classes = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter((r) => r.test(password)).length;
  const rules: PasswordRule[] = [
    { id: "length", label: "长度至少 10 位", passed: password.length >= 10 },
    { id: "classes", label: "包含大写、小写、数字、符号中至少 3 类", passed: classes >= 3 },
  ];
  return { ok: rules.every((r) => r.passed), rules };
}

export const passwordSchema = z
  .string()
  .refine((v) => checkPassword(v).ok, "密码需至少 10 位，且包含大写、小写、数字、符号中至少 3 类");

export const loginSchema = z.object({
  username: z.string().trim().min(1, "请输入用户名").max(64),
  password: z.string().min(1, "请输入密码").max(128),
});

export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, "请输入当前密码"),
    newPassword: passwordSchema,
    confirm: z.string(),
  })
  .superRefine((v, ctx) => {
    if (v.confirm !== v.newPassword) {
      ctx.addIssue({ code: "custom", path: ["confirm"], message: "两次输入的新密码不一致" });
    }
    if (v.newPassword && v.newPassword === v.currentPassword) {
      ctx.addIssue({
        code: "custom",
        path: ["newPassword"],
        message: "新密码不能与当前密码相同",
      });
    }
  });

export type ValidationResult<T> =
  { ok: true; data: T } | { ok: false; errors: Record<string, string> };

/** 校验并把每个字段的第一条错误收集成 { field: message }。 */
export function validate<S extends z.ZodType>(
  schema: S,
  input: unknown,
): ValidationResult<z.output<S>> {
  const result = schema.safeParse(input);
  if (result.success) return { ok: true, data: result.data };
  const errors: Record<string, string> = {};
  for (const issue of result.error.issues) {
    const key = String(issue.path[0] ?? "_form");
    errors[key] ??= issue.message;
  }
  return { ok: false, errors };
}
