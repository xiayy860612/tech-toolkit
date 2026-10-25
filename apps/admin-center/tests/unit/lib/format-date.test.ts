import { formatDateTime, formatRelative } from "@/lib/format-date";

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

describe("formatRelative", () => {
  const now = Date.parse("2026-10-24T12:00:00Z");
  const ago = (ms: number) => new Date(now - ms).toISOString();
  const MIN = 60_000;

  it.each([
    [0, "刚刚"],
    [59_000, "刚刚"],
    [MIN, "1 分钟前"],
    [59 * MIN, "59 分钟前"],
    [60 * MIN, "1 小时前"],
    [23 * 60 * MIN + 59 * MIN, "23 小时前"],
    [24 * 60 * MIN, "1 天前"],
    [3 * 24 * 60 * MIN + 5 * MIN, "3 天前"],
  ])("相差 %i 毫秒 → %s", (diff, expected) => {
    expect(formatRelative(ago(diff), now)).toBe(expected);
  });

  it("晚于当前时间（时钟偏差）按「刚刚」处理", () => {
    expect(formatRelative(new Date(now + 5 * MIN).toISOString(), now)).toBe("刚刚");
  });

  it("无法解析的输入原样返回", () => {
    expect(formatRelative("not a date", now)).toBe("not a date");
  });
});
