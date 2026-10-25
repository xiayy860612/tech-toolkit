import { parsePage, parseSessionRef, parseUserId, parseUsersQuery } from "@/lib/validation/users-query";

describe("parseUsersQuery", () => {
  it("缺省：q 为空、page 为 1", () => {
    expect(parseUsersQuery({})).toEqual({ query: "", page: 1 });
  });

  it("q 去首尾空白，保留中间空白与大小写", () => {
    expect(parseUsersQuery({ q: "  Li Si  " })).toEqual({ query: "Li Si", page: 1 });
  });

  it("q 去掉控制字符；超过 64 个字符截断（按字符而非字节）", () => {
    expect(parseUsersQuery({ q: "a\u0000b\u007fc\td" }).query).toBe("abcd");
    expect(parseUsersQuery({ q: "字".repeat(80) }).query).toBe("字".repeat(64));
  });

  it("数组取第一项；非字符串按缺省", () => {
    expect(parseUsersQuery({ q: ["x", "y"], page: ["3", "4"] })).toEqual({ query: "x", page: 3 });
    expect(parseUsersQuery({ q: [], page: [] })).toEqual({ query: "", page: 1 });
  });

  it.each(["abc", "0", "-1", "1.5", "", " 2", "2 ", "+3", "1e3", "12345678"])("非法 page=%p → 1", (page) => {
    expect(parseUsersQuery({ page }).page).toBe(1);
  });

  it.each([
    ["1", 1],
    ["2", 2],
    ["20", 20],
    ["1234567", 1234567],
  ])("合法 page=%s → %i", (page, expected) => {
    expect(parseUsersQuery({ page }).page).toBe(expected);
  });
});

describe("parseUserId", () => {
  it("合法 UUID 原样返回（大小写不变）", () => {
    expect(parseUserId("11111111-1111-4111-8111-111111111111")).toBe("11111111-1111-4111-8111-111111111111");
    expect(parseUserId("AAAAAAAA-aaaa-4AAA-8aaa-aaaaaaaaaaaa")).toBe("AAAAAAAA-aaaa-4AAA-8aaa-aaaaaaaaaaaa");
  });

  it.each(["", "abc", "11111111-1111-4111-8111-11111111111", "11111111111141118111111111111111", "../etc/passwd", "1111111-1111-4111-8111-1111111111111", "x".repeat(200)])(
    "非法 %p → null",
    (value) => {
      expect(parseUserId(value)).toBeNull();
    },
  );
});

describe("parsePage", () => {
  it("缺省与非法 → 1；合法正整数原样；数组取第一项", () => {
    expect(parsePage(undefined)).toBe(1);
    expect(parsePage("abc")).toBe(1);
    expect(parsePage("0")).toBe(1);
    expect(parsePage("7")).toBe(7);
    expect(parsePage(["4", "5"])).toBe(4);
    expect(parsePage([])).toBe(1);
  });
});

describe("parseSessionRef", () => {
  it("合法的 UUID 原样返回；其余（含想改请求路径的）返回 null", () => {
    expect(parseSessionRef("5d0c8f3a-1b2c-4d3e-8f4a-0123456789ab")).toBe("5d0c8f3a-1b2c-4d3e-8f4a-0123456789ab");
    for (const value of ["", "not-a-uuid", "../users", "5d0c8f3a-1b2c-4d3e-8f4a-0123456789ab/x"]) {
      expect(parseSessionRef(value)).toBeNull();
    }
  });
});
