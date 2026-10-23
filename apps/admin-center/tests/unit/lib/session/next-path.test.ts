import { DEFAULT_NEXT_PATH, safeNextPath } from "@/lib/session/next-path";

describe("safeNextPath：只接受以单个 / 开头的站内路径（FR-012）", () => {
  it.each([
    ["/users"],
    ["/users/0f8fad5b-d9cb-469f-a165-70867728950e"],
    ["/users?q=zhang&page=2"],
    ["/sessions#top"],
    ["/"],
  ])("接受 %s", (path) => {
    expect(safeNextPath(path)).toBe(path);
  });

  it.each([
    ["//evil.example"],
    ["///evil.example"],
    ["/\\evil.example"],
    ["\\\\evil.example"],
    ["/users\\..\\x"],
    ["https://evil.example"],
    ["http://evil.example/users"],
    ["javascript:alert(1)"],
    ["data:text/html,x"],
    ["users"],
    ["./users"],
    [" /users"],
    ["/users\n/x"],
    ["/users\r\nSet-Cookie: a=b"],
    ["/\t/evil.example"],
    ["/users\u0000"],
    ["/users\u007f"],
    [""],
    ["/" + "a".repeat(2100)],
  ])("拒绝 %j，回到默认 /users", (path) => {
    expect(safeNextPath(path)).toBe(DEFAULT_NEXT_PATH);
  });

  it("缺失或非字符串：回到默认", () => {
    expect(DEFAULT_NEXT_PATH).toBe("/users");
    expect(safeNextPath(undefined)).toBe("/users");
    expect(safeNextPath(null)).toBe("/users");
    expect(safeNextPath(["/a", "/b"])).toBe("/users");
    expect(safeNextPath(42 as never)).toBe("/users");
  });

  it("不跟随登录页自身（避免登录后又回登录页）", () => {
    expect(safeNextPath("/login")).toBe("/users");
    expect(safeNextPath("/login?next=/users")).toBe("/users");
    expect(safeNextPath("/loginx")).toBe("/loginx");
  });
});
