import { describe, expect, it } from "vitest";
import { formatDateTime, formatTime, roleLabel, statusLabel } from "@/lib/format";

describe("format", () => {
  it("formats RFC 3339 timestamps as local yyyy-MM-dd HH:mm", () => {
    const d = new Date(2030, 0, 2, 3, 4, 5);
    expect(formatDateTime(d.toISOString())).toBe("2030-01-02 03:04");
  });

  it("formats a short HH:mm time", () => {
    expect(formatTime(new Date(2030, 0, 2, 3, 4, 5).toISOString())).toBe("03:04");
  });

  it("shows a placeholder for missing or invalid timestamps", () => {
    expect(formatDateTime(null)).toBe("—");
    expect(formatDateTime(undefined)).toBe("—");
    expect(formatDateTime("not-a-date")).toBe("—");
    expect(formatDateTime(null, "从未登录")).toBe("从未登录");
  });

  it("labels roles and statuses", () => {
    expect(roleLabel("TENANT_ADMIN")).toBe("租户管理员");
    expect(roleLabel("MEMBER")).toBe("普通用户");
    expect(statusLabel("ACTIVE")).toBe("启用");
    expect(statusLabel("DISABLED")).toBe("停用");
  });
});
