import { describe, expect, it } from "vitest";
import {
  checkPassword,
  changePasswordSchema,
  displayNameSchema,
  loginSchema,
  passwordSchema,
  usernameSchema,
  validate,
} from "@/lib/validation";

describe("username", () => {
  it.each(["abc", "A1b", "a.b-c_d", "zhang.san", "a".repeat(32)])("accepts %s", (v) => {
    expect(usernameSchema.safeParse(v).success).toBe(true);
  });
  it.each(["ab", "", ".abc", "-abc", "a b c", "a".repeat(33), "用户名abc", "a@b.c"])(
    "rejects %j",
    (v) => {
      expect(usernameSchema.safeParse(v).success).toBe(false);
    },
  );
});

describe("display name", () => {
  it("display name is non-empty and at most 50 chars", () => {
    expect(displayNameSchema.safeParse("张三").success).toBe(true);
    expect(displayNameSchema.safeParse("   ").success).toBe(false);
    expect(displayNameSchema.safeParse("").success).toBe(false);
    expect(displayNameSchema.safeParse("x".repeat(50)).success).toBe(true);
    expect(displayNameSchema.safeParse("x".repeat(51)).success).toBe(false);
  });
});

describe("password complexity", () => {
  it("needs length >= 10 and at least 3 of 4 character classes", () => {
    expect(checkPassword("Abcdefg1!x").ok).toBe(true);
    expect(checkPassword("Abcdefghij1").ok).toBe(true); // 大写 + 小写 + 数字
    expect(checkPassword("abcdefghij").ok).toBe(false); // 仅 1 类
    expect(checkPassword("Abcdefghij").ok).toBe(false); // 仅 2 类
    expect(checkPassword("Ab1!").ok).toBe(false); // 太短
  });

  it("reports individual rules for the live checklist", () => {
    const r = checkPassword("abc");
    expect(r.rules.map((x) => x.id)).toEqual(["length", "classes"]);
    expect(r.rules.every((x) => !x.passed)).toBe(true);
    expect(checkPassword("Abcdefg1!x").rules.every((x) => x.passed)).toBe(true);
  });

  it("schema mirrors the checks", () => {
    expect(passwordSchema.safeParse("Abcdefg1!x").success).toBe(true);
    expect(passwordSchema.safeParse("short").success).toBe(false);
  });
});

describe("form schemas and validate()", () => {
  it("login requires both fields", () => {
    expect(validate(loginSchema, { username: "", password: "" }).ok).toBe(false);
    expect(validate(loginSchema, { username: "admin", password: "x" }).ok).toBe(true);
  });

  it("change-password needs matching confirmation and a different new password", () => {
    const base = {
      currentPassword: "Old-Passw0rd!",
      newPassword: "New-Passw0rd!x",
      confirm: "New-Passw0rd!x",
    };
    expect(validate(changePasswordSchema, base).ok).toBe(true);

    const mismatch = validate(changePasswordSchema, { ...base, confirm: "other" });
    expect(mismatch.ok).toBe(false);
    if (!mismatch.ok) expect(mismatch.errors.confirm).toBeTruthy();

    const same = validate(changePasswordSchema, {
      currentPassword: "Same-Passw0rd!",
      newPassword: "Same-Passw0rd!",
      confirm: "Same-Passw0rd!",
    });
    expect(same.ok).toBe(false);
    if (!same.ok) expect(same.errors.newPassword).toBeTruthy();
  });

  it("returns the first message per field", () => {
    const r = validate(loginSchema, { username: "", password: "" });
    if (!r.ok) {
      expect(Object.keys(r.errors).sort()).toEqual(["password", "username"]);
    }
  });
});
