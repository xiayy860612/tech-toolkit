/**
 * @jest-environment node
 */
import { headers } from "next/headers";
import { createUser, disableUser, enableUser, getUser, listOnlineUsers, listUserSessions, listUsers, uamRequest, verifySession } from "@/lib/uam/client";
import { UamError, uamErrorMessage, fieldReasonMessage } from "@/lib/uam/errors";
import {
  createUamStub,
  FIXTURE_IDS,
  problemBody,
  UAM_STUB_BASE_URL,
  UAM_STUB_SERVICE_KEY,
  verifyResponseBody,
} from "../../../support/uam-stub";

jest.mock("next/headers", () => ({ headers: jest.fn() }));

const stub = createUamStub();
const saved = { ...process.env };
const operator = { userId: FIXTURE_IDS.userId, accountId: FIXTURE_IDS.accountId, sessionRef: FIXTURE_IDS.sessionRef };
const userBody = { id: FIXTURE_IDS.userId, name: "李四", status: "DISABLED", createdAt: "2026-10-24T00:00:00Z", accounts: [] };

beforeAll(() => stub.listen());
afterAll(() => stub.close());
beforeEach(() => {
  process.env.UAM_BASE_URL = UAM_STUB_BASE_URL;
  process.env.UAM_SERVICE_KEY = UAM_STUB_SERVICE_KEY;
  process.env.UAM_TIMEOUT_MS = "1500";
  jest.mocked(headers).mockRejectedValue(new Error("outside request scope"));
});
afterEach(() => {
  expect(stub.violations).toEqual([]);
  stub.reset();
  process.env = { ...saved };
});

describe("请求头", () => {
  it("每个请求都带 Authorization: Bearer <UAM_SERVICE_KEY>（读与写）", async () => {
    stub.respond("listUsers", { status: 200, body: { items: [], page: 1, size: 20, total: 0 } });
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    await uamRequest({ method: "GET", path: "/v1/users" });
    await verifySession("sid");
    expect(stub.calls).toHaveLength(2);
    for (const call of stub.calls) {
      expect(call.headers.get("authorization")).toBe(`Bearer ${UAM_STUB_SERVICE_KEY}`);
    }
  });

  it("写操作带 X-Operator-User-Id / Account-Id / Session-Ref", async () => {
    stub.respond("disableUser", { status: 200, body: userBody });
    await uamRequest({ method: "POST", path: `/v1/users/${FIXTURE_IDS.userId}/disable`, operator });
    const h = stub.calls[0].headers;
    expect(h.get("x-operator-user-id")).toBe(operator.userId);
    expect(h.get("x-operator-account-id")).toBe(operator.accountId);
    expect(h.get("x-operator-session-ref")).toBe(operator.sessionRef);
  });

  it("没传操作人时不带 X-Operator-*（verify 之类的调用）", async () => {
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    await verifySession("sid");
    expect(stub.calls[0].headers.get("x-operator-user-id")).toBeNull();
  });

  it("自定义请求头（Idempotency-Key）原样传递", async () => {
    stub.respond("createUser", { status: 201, body: userBody });
    await uamRequest({
      method: "POST",
      path: "/v1/users",
      operator,
      headers: { "Idempotency-Key": "0123456789abcdef" },
      body: { userName: "李四", accountName: "lisi", password: "Passw0rd-demo1" },
    });
    expect(stub.calls[0].headers.get("idempotency-key")).toBe("0123456789abcdef");
  });

  it("传递当前请求的 traceparent", async () => {
    const tp = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    jest.mocked(headers).mockResolvedValue(new Headers({ traceparent: tp }) as never);
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    await verifySession("sid");
    expect(stub.calls[0].headers.get("traceparent")).toBe(tp);
  });

  it("显式传入的 traceparent 优先", async () => {
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    await uamRequest({
      method: "POST",
      path: "/v1/auth/sessions/verify",
      body: { sessionId: "s" },
      traceparent: "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
    });
    expect(stub.calls[0].headers.get("traceparent")).toContain("aaaaaaaa");
  });

  it("没有 traceparent 时不带这个头", async () => {
    jest.mocked(headers).mockResolvedValue(new Headers() as never);
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    await verifySession("sid");
    expect(stub.calls[0].headers.get("traceparent")).toBeNull();
  });
});

describe("请求与响应处理", () => {
  it("把查询参数拼进 URL，忽略 undefined", async () => {
    stub.respond("listUsers", { status: 200, body: { items: [], page: 2, size: 20, total: 0 } });
    await uamRequest({ method: "GET", path: "/v1/users", query: { page: 2, query: "li", size: undefined } });
    expect(stub.calls[0].query.get("page")).toBe("2");
    expect(stub.calls[0].query.get("query")).toBe("li");
    expect(stub.calls[0].query.has("size")).toBe(false);
  });

  it("204 没有响应体时 data 为 undefined", async () => {
    stub.respond("terminateSession", { status: 204 });
    const res = await uamRequest({ method: "DELETE", path: `/v1/sessions/${FIXTURE_IDS.sessionRef}`, operator });
    expect(res.status).toBe(204);
    expect(res.data).toBeUndefined();
  });
});

describe("错误归类", () => {
  const read = () => uamRequest({ method: "GET", path: "/v1/users" });

  it("超时（UAM_TIMEOUT_MS）→ unavailable", async () => {
    process.env.UAM_TIMEOUT_MS = "40";
    stub.respond("listUsers", { status: 200, body: { items: [], page: 1, size: 20, total: 0 }, delayMs: 400 });
    await expect(read()).rejects.toMatchObject({ kind: "unavailable" });
  });

  it("默认超时是 1.5 秒", async () => {
    delete process.env.UAM_TIMEOUT_MS;
    const spy = jest.spyOn(AbortSignal, "timeout");
    stub.respond("listUsers", { status: 200, body: { items: [], page: 1, size: 20, total: 0 } });
    await read();
    expect(spy).toHaveBeenCalledWith(1500);
    spy.mockRestore();
  });

  it("连接失败 → unavailable", async () => {
    stub.respond("listUsers", { networkError: true });
    await expect(read()).rejects.toMatchObject({ kind: "unavailable" });
  });

  it("5xx → unavailable", async () => {
    stub.respond("listUsers", { status: 500, body: undefined });
    // 契约没声明 500：只测归类，清掉桩记录的违约
    await expect(read()).rejects.toMatchObject({ kind: "unavailable", status: 500 });
    stub.violations.length = 0;
  });

  it("401 client_unauthenticated → misconfigured（不是 session_invalid）", async () => {
    process.env.UAM_SERVICE_KEY = "wrong-key"; // 桩会回 401 client_unauthenticated
    stub.respond("listUsers", { status: 200, body: { items: [], page: 1, size: 20, total: 0 } });
    const error = await read().catch((e) => e);
    expect(error).toBeInstanceOf(UamError);
    expect(error).toMatchObject({ kind: "misconfigured", status: 401, code: "client_unauthenticated" });
    expect(error.code).not.toBe("session_invalid");
  });

  it("403 client_forbidden → misconfigured", async () => {
    stub.respond("verifySession", { status: 403, body: problemBody(403, "client_forbidden") });
    await expect(verifySession("s")).rejects.toMatchObject({ kind: "misconfigured", code: "client_forbidden" });
  });

  it("401 session_invalid 与业务问题码 → problem，携带 code、字段错误、Retry-After", async () => {
    stub.respond("verifySession", { status: 401, body: problemBody(401, "session_invalid") });
    await expect(verifySession("s")).rejects.toMatchObject({ kind: "problem", code: "session_invalid" });

    stub.respond("createUser", {
      status: 422,
      body: problemBody(422, "validation_failed", { errors: [{ field: "accountName", reason: "too_short" }] }),
    });
    await expect(
      uamRequest({
        method: "POST",
        path: "/v1/users",
        operator,
        headers: { "Idempotency-Key": "0123456789abcdef" },
        body: { userName: "a", accountName: "ab", password: "x" },
      }),
    ).rejects.toMatchObject({ kind: "problem", code: "validation_failed", fieldErrors: [{ field: "accountName", reason: "too_short" }] });

    stub.respond("login", { status: 429, body: problemBody(429, "throttled"), headers: { "Retry-After": "42" } });
    await expect(
      uamRequest({ method: "POST", path: "/v1/auth/login", body: { accountName: "a", password: "b", clientIp: "1.1.1.1" } }),
    ).rejects.toMatchObject({ kind: "problem", code: "throttled", retryAfter: 42 });
  });

  it("4xx 但没有可解析的问题体 → unavailable（意料之外的响应不当作业务结论）", async () => {
    stub.respond("getUser", { status: 404 });
    stub.violations.length = 0;
    const error = await uamRequest({ method: "GET", path: `/v1/users/${FIXTURE_IDS.userId}` }).catch((e) => e);
    expect(error).toMatchObject({ kind: "unavailable", status: 404 });
    stub.violations.length = 0;
  });

  it("2xx 但响应体不是 JSON → unavailable", async () => {
    const original = global.fetch;
    global.fetch = jest.fn(async () => new Response("<html>oops</html>", { status: 200 })) as never;
    await expect(read()).rejects.toMatchObject({ kind: "unavailable" });
    global.fetch = original;
  });
});

describe("listUsers / getUser（读用户，T081）", () => {
  const summary = { id: FIXTURE_IDS.userId, name: "张三", status: "ACTIVE", accountCount: 2, createdAt: "2026-10-01T08:30:00Z" };

  it("listUsers：把搜索词映射为 UAM 的 query、页码为 page；返回 UAM 的分页数据", async () => {
    const body = { items: [summary], page: 2, size: 20, total: 21 };
    stub.respond("listUsers", { status: 200, body });
    await expect(listUsers({ query: "Li", page: 2 })).resolves.toEqual(body);
    expect(stub.calls[0].path).toBe("/v1/users");
    expect(stub.calls[0].query.get("query")).toBe("Li");
    expect(stub.calls[0].query.get("page")).toBe("2");
    expect(stub.calls[0].query.has("size")).toBe(false);
  });

  it("listUsers：无参数 / 空搜索词时不带查询参数；带服务密钥，不带 X-Operator-*", async () => {
    stub.respond("listUsers", { status: 200, body: { items: [], page: 1, size: 20, total: 0 } });
    await listUsers();
    await listUsers({ query: "" });
    for (const call of stub.calls) {
      expect([...call.query.keys()]).toEqual([]);
      expect(call.headers.get("authorization")).toBe(`Bearer ${UAM_STUB_SERVICE_KEY}`);
      expect(call.headers.get("x-operator-user-id")).toBeNull();
    }
  });

  it("listUsers：size 可指定", async () => {
    stub.respond("listUsers", { status: 200, body: { items: [], page: 1, size: 50, total: 0 } });
    await listUsers({ size: 50 });
    expect(stub.calls[0].query.get("size")).toBe("50");
  });

  it("listUsers：UAM 不可用 → UamError(unavailable)", async () => {
    stub.respond("listUsers", { networkError: true });
    await expect(listUsers()).rejects.toMatchObject({ kind: "unavailable" });
  });

  it("getUser：取详情（含全部账号），路径参数做编码", async () => {
    const body = { ...summary, accounts: [{ id: FIXTURE_IDS.accountId, loginName: "zhangsan", kind: "PASSWORD", status: "ACTIVE", createdAt: "2026-10-01T08:30:00Z" }] };
    delete (body as Record<string, unknown>).accountCount;
    stub.respond("getUser", { status: 200, body });
    await expect(getUser(FIXTURE_IDS.userId)).resolves.toEqual(body);
    expect(stub.calls[0].path).toBe(`/v1/users/${FIXTURE_IDS.userId}`);
  });

  it("getUser：404 user_not_found → UamError(problem, user_not_found)", async () => {
    stub.respond("getUser", { status: 404, body: problemBody(404, "user_not_found") });
    await expect(getUser(FIXTURE_IDS.userId)).rejects.toMatchObject({ kind: "problem", status: 404, code: "user_not_found" });
  });

  it("getUser：userId 里的特殊字符不能改变请求路径", async () => {
    const spy = jest.spyOn(global, "fetch").mockResolvedValue(new Response(JSON.stringify(problemBody(404, "user_not_found")), { status: 404 }));
    await getUser("../sessions?x=1").catch(() => undefined);
    expect(String(spy.mock.calls[0][0])).toContain("/v1/users/..%2Fsessions%3Fx%3D1");
    spy.mockRestore();
  });
});

describe("listOnlineUsers / listUserSessions（读会话，T093）", () => {
  const online = { userId: FIXTURE_IDS.userId, userName: "张三", sessionCount: 2, lastActiveAt: "2026-10-24T11:55:00Z" };
  const session = {
    sessionRef: FIXTURE_IDS.sessionRef,
    accountId: FIXTURE_IDS.accountId,
    accountName: "zhangsan",
    accountKind: "PASSWORD",
    loginAt: "2026-10-24T10:00:00Z",
    lastActiveAt: "2026-10-24T11:55:00Z",
    expiresAt: "2026-10-24T22:00:00Z",
    current: true,
  };

  it("listOnlineUsers：页码映射为 page、size 可指定；返回 UAM 的分页数据；带服务密钥，不带 X-Operator-*", async () => {
    const body = { items: [online], page: 2, size: 50, total: 51 };
    stub.respond("listOnlineUsers", { status: 200, body });
    await expect(listOnlineUsers({ page: 2, size: 50 })).resolves.toEqual(body);
    const call = stub.calls[0];
    expect(call.path).toBe("/v1/online-users");
    expect(call.query.get("page")).toBe("2");
    expect(call.query.get("size")).toBe("50");
    expect(call.headers.get("authorization")).toBe(`Bearer ${UAM_STUB_SERVICE_KEY}`);
    expect(call.headers.get("x-operator-user-id")).toBeNull();
  });

  it("listOnlineUsers：无参数时不带查询参数", async () => {
    stub.respond("listOnlineUsers", { status: 200, body: { items: [], page: 1, size: 20, total: 0 } });
    await listOnlineUsers();
    expect([...stub.calls[0].query.keys()]).toEqual([]);
  });

  it("listOnlineUsers：UAM 不可用 → UamError(unavailable)", async () => {
    stub.respond("listOnlineUsers", { networkError: true });
    await expect(listOnlineUsers()).rejects.toMatchObject({ kind: "unavailable" });
  });

  it("listUserSessions：带操作人请求头（UAM 据会话引用算 current），返回会话数组", async () => {
    stub.respond("listUserSessions", { status: 200, body: { items: [session] } });
    await expect(listUserSessions(FIXTURE_IDS.userId, operator)).resolves.toEqual([session]);
    const call = stub.calls[0];
    expect(call.path).toBe(`/v1/users/${FIXTURE_IDS.userId}/sessions`);
    expect(call.headers.get("authorization")).toBe(`Bearer ${UAM_STUB_SERVICE_KEY}`);
    expect(call.headers.get("x-operator-session-ref")).toBe(operator.sessionRef);
    expect(call.headers.get("x-operator-user-id")).toBe(operator.userId);
    expect(call.headers.get("x-operator-account-id")).toBe(operator.accountId);
  });

  it("listUserSessions：404 user_not_found → UamError(problem, user_not_found)", async () => {
    stub.respond("listUserSessions", { status: 404, body: problemBody(404, "user_not_found") });
    await expect(listUserSessions(FIXTURE_IDS.userId, operator)).rejects.toMatchObject({ kind: "problem", status: 404, code: "user_not_found" });
  });

  it("listUserSessions：userId 里的特殊字符不能改变请求路径", async () => {
    const spy = jest.spyOn(global, "fetch").mockResolvedValue(new Response(JSON.stringify(problemBody(404, "user_not_found")), { status: 404 }));
    await listUserSessions("../x?y=1", operator).catch(() => undefined);
    expect(String(spy.mock.calls[0][0])).toContain("/v1/users/..%2Fx%3Fy%3D1/sessions");
    spy.mockRestore();
  });
});

describe("问题码 → 界面文案（与 contracts/web-routes.md §5 一致）", () => {
  const problem = (code: string, extra: Partial<UamError> = {}) =>
    new UamError("problem", code, { code, ...extra });

  it.each([
    ["invalid_credentials", "账号名或密码错误"],
    ["session_invalid", "会话已结束，请重新登录"],
    ["account_name_taken", "账号名已被使用"],
    ["cannot_disable_self", "不能停用当前登录的用户"],
    ["cannot_disable_last_active_user", "不能停用最后一个正常用户"],
    ["cannot_terminate_own_session", "这是你当前使用的会话，请改用「退出登录」"],
    ["session_not_found", "该会话已结束，已刷新为最新状态"],
    ["user_not_found", "用户不存在"],
    ["current_password_incorrect", "当前密码不正确"],
    ["weak_password", "新密码不满足强度要求"],
    ["same_as_current", "新密码不能与当前密码相同"],
  ])("%s → %s", (code, message) => {
    expect(uamErrorMessage(problem(code), "write")).toBe(message);
    expect(uamErrorMessage(problem(code), "read")).toBe(message);
  });

  it("throttled 带剩余秒数", () => {
    expect(uamErrorMessage(problem("throttled", { retryAfter: 30 }), "write")).toBe("尝试过于频繁，请 30 秒后再试");
    expect(uamErrorMessage(problem("throttled"), "write")).toBe("尝试过于频繁，请稍后再试");
  });

  it("validation_failed 给出总提示，字段原因另有映射", () => {
    expect(uamErrorMessage(problem("validation_failed"), "write")).toBe("输入有误，请按提示修改后重试");
    expect(fieldReasonMessage("required")).toBe("此项必填");
    expect(fieldReasonMessage("too_long")).toBe("内容过长");
    expect(fieldReasonMessage("invalid_chars")).toBe("含有不允许的字符");
    expect(fieldReasonMessage("too_short")).toBe("长度不足");
    expect(fieldReasonMessage("too_weak")).toBe("强度不足");
    expect(fieldReasonMessage("taken")).toBe("账号名已被使用");
    expect(fieldReasonMessage("whatever")).toBe("输入不合法");
  });

  it.each([
    ["unavailable", "read", "服务暂不可用，请稍后重试"],
    ["unavailable", "write", "操作未生效"],
    ["misconfigured", "read", "服务暂不可用，请稍后重试"],
    ["misconfigured", "write", "操作未生效"],
  ] as const)("%s（%s）→ %s", (kind, mode, message) => {
    expect(uamErrorMessage(new UamError(kind, "x"), mode)).toBe(message);
  });

  it("未知问题码按不可用处理", () => {
    expect(uamErrorMessage(problem("brand_new_code"), "write")).toBe("操作未生效");
    expect(uamErrorMessage(problem("brand_new_code"), "read")).toBe("服务暂不可用，请稍后重试");
  });
});

describe("createUser", () => {
  const input = { userName: "王五", accountName: "wangwu", password: "Sup3r-Secret-pass" };
  const KEY = "11111111-2222-4333-8444-555555555555";

  it("POST /v1/users：带服务密钥、操作人请求头与 Idempotency-Key，请求体即输入；返回新用户", async () => {
    stub.respond("createUser", { status: 201, body: { ...userBody, status: "ACTIVE" } });
    const user = await createUser(input, operator, KEY);
    expect(user.id).toBe(FIXTURE_IDS.userId);
    const [call] = stub.calls;
    expect(call.method).toBe("POST");
    expect(call.path).toBe("/v1/users");
    expect(call.headers.get("authorization")).toBe(`Bearer ${UAM_STUB_SERVICE_KEY}`);
    expect(call.headers.get("idempotency-key")).toBe(KEY);
    expect(call.headers.get("x-operator-user-id")).toBe(operator.userId);
    expect(call.body).toEqual(input);
  });

  it("409 与 422 抛出带字段级错误的 UamError；错误里没有密码", async () => {
    stub.respond("createUser", { status: 409, body: problemBody(409, "account_name_taken") });
    await expect(createUser(input, operator, KEY)).rejects.toMatchObject({ kind: "problem", code: "account_name_taken", status: 409 });

    stub.respond("createUser", {
      status: 422,
      body: problemBody(422, "validation_failed", { errors: [{ field: "password", reason: "too_weak" }] }),
    });
    const error = await createUser(input, operator, KEY).catch((e: UamError) => e);
    expect(error).toMatchObject({ code: "validation_failed", fieldErrors: [{ field: "password", reason: "too_weak" }] });
    expect(JSON.stringify(error)).not.toContain(input.password);
  });
});

describe("disableUser / enableUser（T122）", () => {
  const id = FIXTURE_IDS.userId;

  it.each([
    ["disableUser", disableUser, "disable", "DISABLED"],
    ["enableUser", enableUser, "enable", "ACTIVE"],
  ] as const)("%s：POST /v1/users/{id}/%s，带服务密钥与操作人请求头，没有请求体；返回用户详情", async (operationId, call, suffix, status) => {
    stub.respond(operationId, { status: 200, body: { ...userBody, status } });
    const user = await call(id, operator);
    expect(user.status).toBe(status);
    const [sent] = stub.calls;
    expect(sent.method).toBe("POST");
    expect(sent.path).toBe(`/v1/users/${id}/${suffix}`);
    expect(sent.headers.get("authorization")).toBe(`Bearer ${UAM_STUB_SERVICE_KEY}`);
    expect(sent.headers.get("x-operator-user-id")).toBe(operator.userId);
    expect(sent.headers.get("x-operator-account-id")).toBe(operator.accountId);
    expect(sent.headers.get("x-operator-session-ref")).toBe(operator.sessionRef);
    expect(sent.body).toBeUndefined();
  });

  it("409 与 404 抛出带问题码的 UamError", async () => {
    stub.respond("disableUser", { status: 409, body: problemBody(409, "cannot_disable_last_active_user") });
    await expect(disableUser(id, operator)).rejects.toMatchObject({ kind: "problem", code: "cannot_disable_last_active_user", status: 409 });
    stub.respond("enableUser", { status: 404, body: problemBody(404, "user_not_found") });
    await expect(enableUser(id, operator)).rejects.toMatchObject({ kind: "problem", code: "user_not_found" });
  });

  it("userId 里的特殊字符不能改变请求路径", async () => {
    const spy = jest.spyOn(global, "fetch").mockResolvedValue(new Response(JSON.stringify(problemBody(404, "user_not_found")), { status: 404 }));
    await disableUser("../x?y=1", operator).catch(() => undefined);
    await enableUser("../x?y=1", operator).catch(() => undefined);
    expect(String(spy.mock.calls[0][0])).toContain("/v1/users/..%2Fx%3Fy%3D1/disable");
    expect(String(spy.mock.calls[1][0])).toContain("/v1/users/..%2Fx%3Fy%3D1/enable");
    spy.mockRestore();
  });
});
