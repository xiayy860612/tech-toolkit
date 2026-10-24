/**
 * @jest-environment node
 */
import { uamRequest, verifySession } from "@/lib/uam/client";
import {
  createUamStub,
  FIXTURE_IDS,
  problemBody,
  UAM_STUB_BASE_URL,
  UAM_STUB_SERVICE_KEY,
  verifyResponseBody,
} from "../support/uam-stub";

jest.mock("next/headers", () => ({
  headers: jest.fn(async () => {
    throw new Error("outside request scope");
  }),
}));

const stub = createUamStub();
const saved = { ...process.env };

beforeAll(() => stub.listen());
afterAll(() => stub.close());
beforeEach(() => {
  process.env.UAM_BASE_URL = UAM_STUB_BASE_URL;
  process.env.UAM_SERVICE_KEY = UAM_STUB_SERVICE_KEY;
  process.env.UAM_TIMEOUT_MS = "1500";
});
afterEach(() => {
  expect(stub.violations).toEqual([]);
  stub.reset();
  process.env = { ...saved };
});

describe("UAM 桩本身忠于 OpenAPI 契约", () => {
  it("覆盖契约里的全部操作", () => {
    expect([...stub.operationIds].sort()).toEqual(
      [
        "changeOwnPassword",
        "createUser",
        "disableUser",
        "enableUser",
        "getUser",
        "listOnlineUsers",
        "listUserSessions",
        "listUsers",
        "login",
        "logout",
        "terminateSession",
        "verifySession",
      ].sort(),
    );
  });

  it("记录违反契约的请求体（verify 缺 sessionId）", async () => {
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    await expect(uamRequest({ method: "POST", path: "/v1/auth/sessions/verify", body: { wrong: 1 } })).rejects.toMatchObject({
      kind: "problem",
      status: 422,
      code: "validation_failed",
    });
    expect(stub.violations).toHaveLength(1);
    expect(stub.violations[0]).toContain("请求体");
    stub.violations.length = 0; // 这是预期的违约，清掉以免 afterEach 失败
  });

  it("记录契约里没有声明的响应状态码与不合 schema 的响应体", async () => {
    stub.respond("verifySession", { status: 418, body: {} });
    await expect(verifySession("sid")).rejects.toBeDefined();
    stub.respond("verifySession", { status: 200, body: { user: {} } });
    await verifySession("sid").catch(() => undefined);
    expect(stub.violations.some((v) => v.includes("状态码未在契约中声明"))).toBe(true);
    expect(stub.violations.some((v) => v.includes("200"))).toBe(true);
    stub.violations.length = 0;
  });

  it("没配置响应的操作记为违约", async () => {
    await verifySession("sid").catch(() => undefined);
    expect(stub.violations[0]).toContain("没有配置响应");
    stub.violations.length = 0;
  });

  it("配置不存在的操作时直接报错", () => {
    expect(() => stub.respond("nope", { status: 200 })).toThrow("nope");
  });

  it("写操作缺操作人请求头时回 400 operator_required；有则放行", async () => {
    stub.respond("disableUser", {
      status: 200,
      body: { id: FIXTURE_IDS.userId, name: "李四", status: "DISABLED", createdAt: "2026-10-24T00:00:00Z", accounts: [] },
    });
    await expect(
      uamRequest({ method: "POST", path: `/v1/users/${FIXTURE_IDS.userId}/disable` }),
    ).rejects.toMatchObject({ kind: "problem", code: "operator_required" });

    const ok = await uamRequest({
      method: "POST",
      path: `/v1/users/${FIXTURE_IDS.userId}/disable`,
      operator: { userId: FIXTURE_IDS.userId, accountId: FIXTURE_IDS.accountId, sessionRef: FIXTURE_IDS.sessionRef },
    });
    expect(ok.status).toBe(200);
  });

  it("校验路径参数 / 查询参数 / 声明的响应头", async () => {
    stub.respond("getUser", { status: 404, body: problemBody(404, "user_not_found") });
    // 读不懂的请求（非 UUID 路径变量）：桩像真实 UAM 一样回 400 bad_request
    await expect(uamRequest({ method: "GET", path: "/v1/users/not-a-uuid" })).rejects.toMatchObject({
      kind: "problem",
      status: 400,
      code: "bad_request",
    });
    expect(stub.violations[0]).toContain("路径参数 userId");
    stub.violations.length = 0;

    stub.respond("listUsers", { status: 200, body: { items: [], page: 1, size: 20, total: 0 } });
    await expect(uamRequest({ method: "GET", path: "/v1/users", query: { page: 0 } })).rejects.toMatchObject({ status: 422, code: "validation_failed" });
    await expect(uamRequest({ method: "GET", path: "/v1/users", query: { page: "abc" } })).rejects.toMatchObject({ status: 400, code: "bad_request" });
    await uamRequest({ method: "GET", path: "/v1/users", query: { bogus: "x" } }).catch(() => undefined);
    expect(stub.violations.map((v) => v.split("：")[1])).toEqual(
      expect.arrayContaining([expect.stringContaining("page"), expect.stringContaining("page"), expect.stringContaining("bogus")]),
    );
    stub.violations.length = 0;

    stub.respond("login", { status: 429, body: problemBody(429, "throttled") });
    await uamRequest({
      method: "POST",
      path: "/v1/auth/login",
      body: { accountName: "a", password: "b", clientIp: "1.1.1.1" },
    }).catch(() => undefined);
    expect(stub.violations[0]).toContain("Retry-After");
    stub.violations.length = 0;
  });
});

describe("UamClient 契约", () => {
  it("verifySession：请求体与响应都符合契约，且带服务密钥", async () => {
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    const res = await verifySession("opaque-session-id");
    expect(res.user.name).toBe("张三");
    expect(stub.calls[0].body).toEqual({ sessionId: "opaque-session-id" });
    expect(stub.calls[0].headers.get("authorization")).toBe(`Bearer ${UAM_STUB_SERVICE_KEY}`);
  });

  it("verifySession：401 session_invalid 解析为问题类错误", async () => {
    stub.respond("verifySession", { status: 401, body: problemBody(401, "session_invalid") });
    await expect(verifySession("x")).rejects.toMatchObject({ kind: "problem", code: "session_invalid", status: 401 });
  });
});
