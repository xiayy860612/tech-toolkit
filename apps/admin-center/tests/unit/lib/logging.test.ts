/**
 * @jest-environment node
 *
 * `logUamMisconfigured` 的细节（T176 的补充）：字段清洗、限流窗口与表容量。
 * 端到端的行为（不登出、不清 cookie、文案）见 tests/unit/lib/session/misconfig.test.ts。
 */
import { logUamMisconfigured, resetUamMisconfiguredThrottle } from "@/lib/logging";

let error: jest.SpyInstance;
const lines = () => error.mock.calls.map((c) => JSON.parse(String(c[0])) as Record<string, unknown>);

beforeEach(() => {
  resetUamMisconfiguredThrottle();
  error = jest.spyOn(console, "error").mockImplementation(() => undefined);
});
afterEach(() => {
  error.mockRestore();
  jest.restoreAllMocks();
});

const base = { method: "POST", path: "/v1/users", status: 403, code: "client_forbidden" };

describe("logUamMisconfigured：字段", () => {
  it("一行结构化 JSON：事件名、码、状态、操作、时间", () => {
    logUamMisconfigured(base);
    expect(error).toHaveBeenCalledTimes(1);
    expect(error.mock.calls[0]).toHaveLength(1);
    expect(lines()[0]).toMatchObject({
      level: "error",
      event: "admin-center.uam.misconfigured",
      code: "client_forbidden",
      status: 403,
      operation: "POST /v1/users",
    });
    expect(lines()[0]).not.toHaveProperty("traceId");
    expect(lines()[0]).not.toHaveProperty("suppressed");
  });

  it.each([
    [401, undefined, "client_unauthenticated"],
    [403, undefined, "client_forbidden"],
    [401, "Some Weird Code!", "client_unauthenticated"],
    [403, "x".repeat(65), "client_forbidden"],
    [401, "client_unauthenticated", "client_unauthenticated"],
    [401, "other_code", "other_code"],
  ])("状态 %s、码 %j → 记为 %s（来自 UAM 的字符串先校验，不能原样信任）", (status, code, expected) => {
    logUamMisconfigured({ ...base, status, code });
    expect(lines()[0].code).toBe(expected);
  });

  it.each([
    ["/v1/auth/login", "/v1/auth/login"],
    ["/v1/users/3f2a1c9e-1111-4222-8333-444455556666/disable", "/v1/users/:id/disable"],
    ["/v1/sessions/abc123", "/v1/sessions/:id"],
    ["/v1/accounts/whatever%2Fvalue/password", "/v1/accounts/:id/password"],
    ["/v1/online-users", "/v1/online-users"],
  ])("路径 %s 里的标识符泛化为 :id（%s）", (path, expected) => {
    logUamMisconfigured({ ...base, path });
    expect(lines()[0].operation).toBe(`POST ${expected}`);
  });

  it("traceparent 只取合法的 trace id；不合法的丢弃", () => {
    logUamMisconfigured({ ...base, traceparent: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" });
    logUamMisconfigured({ ...base, path: "/v1/auth/login", traceparent: "garbage with secret" });
    logUamMisconfigured({ ...base, path: "/v1/online-users", traceparent: "00-00000000000000000000000000000000-00f067aa0ba902b7-01" });
    const [first, second, third] = lines();
    expect(first.traceId).toBe("4bf92f3577b34da6a3ce929d0e0e4736");
    expect(second).not.toHaveProperty("traceId");
    expect(third).not.toHaveProperty("traceId"); // 全零 trace id 无效
    expect(JSON.stringify(lines())).not.toContain("secret");
  });
});

describe("logUamMisconfigured：限流", () => {
  it("窗口内同键只写一条，窗口后写一条并带 suppressed（含被抑制的次数）", () => {
    const now = jest.spyOn(Date, "now");
    now.mockReturnValue(1_000_000);
    for (let i = 0; i < 4; i++) logUamMisconfigured(base); // 1 条 + 3 次抑制
    expect(lines()).toHaveLength(1);
    now.mockReturnValue(1_000_000 + 59_999);
    logUamMisconfigured(base); // 第 4 次抑制
    expect(lines()).toHaveLength(1);
    now.mockReturnValue(1_000_000 + 60_000);
    logUamMisconfigured(base);
    expect(lines()).toHaveLength(2);
    expect(lines()[1].suppressed).toBe(4);
  });

  it("键 = 码 + 操作：换码、换操作各写各的", () => {
    logUamMisconfigured(base);
    logUamMisconfigured({ ...base, status: 401, code: "client_unauthenticated" });
    logUamMisconfigured({ ...base, path: "/v1/users/abc/disable" });
    expect(lines()).toHaveLength(3);
  });

  it("表容量有上限：键被打满时丢弃最旧的，内存不会无限增长，仍能写日志", () => {
    const now = jest.spyOn(Date, "now");
    now.mockReturnValue(5_000_000);
    for (let i = 0; i < 400; i++) logUamMisconfigured({ ...base, code: `code_${i}` });
    expect(lines()).toHaveLength(400);
    // 最旧的键已被淘汰：再来一次会重新写（而不是被抑制）
    logUamMisconfigured({ ...base, code: "code_0" });
    expect(lines()).toHaveLength(401);
    // 最近的键仍在表里：被抑制
    logUamMisconfigured({ ...base, code: "code_399" });
    expect(lines()).toHaveLength(401);
  });
});
