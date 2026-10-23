import { formatDateTime } from "@/lib/format-date";

describe("formatDateTime", () => {
  it("按运行环境的本地时区格式化为 YYYY-MM-DD HH:mm（补零）", () => {
    const iso = "2026-03-05T07:09:00Z";
    const d = new Date(iso);
    const pad = (n: number) => String(n).padStart(2, "0");
    expect(formatDateTime(iso)).toBe(
      `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`,
    );
    expect(formatDateTime(iso)).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  });

  it("无法解析的输入原样返回（不抛错、不显示 Invalid Date）", () => {
    expect(formatDateTime("not a date")).toBe("not a date");
  });
});
