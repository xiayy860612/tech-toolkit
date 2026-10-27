/**
 * @jest-environment node
 */
import { headers } from "next/headers";
import { changePassword, createUser, disableUser, enableUser, getUser, listOnlineUsers, listUserSessions, listUsers, logout, terminateSession, uamRequest, verifySession } from "@/lib/uam/client";
import { UamError, uamErrorMessage, fieldReasonMessage } from "@/lib/uam/errors";
import {
  createUamStub,
  FIXTURE_IDS,
  problemBody,
  UAM_STUB_BASE_URL,
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
  process.env.UAM_TIMEOUT_MS = "1500";
  jest.mocked(headers).mockRejectedValue(new Error("outside request scope"));
});
afterEach(() => {
  expect(stub.violations).toEqual([]);
  stub.reset();
  process.env = { ...saved };
});

describe("请求头", () => {
  it("不带 Authorization（UAM 不认证调用方；读与写都一样）", async () => {
    stub.respond("listUsers", { status: 200, body: { items: [], page: 1, size: 20, total: 0 } });
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    await uamRequest({ method: "GET", path: "/v1/users" });
    await verifySession("sid");
    expect(stub.calls).toHaveLength(2);
    for (const call of stub.calls) {
      expect(call.headers.get("authorization")).toBeNull();
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

  it("400 bad_request → problem（不是凭据错误），不带字段错误，也不写运维告警", async () => {
    const spy = jest.spyOn(console, "error").mockImplementation(() => undefined);
    stub.respond("listUsers", { status: 400, body: problemBody(400, "bad_request") });
    const error = await read().catch((e) => e);
    expect(error).toBeInstanceOf(UamError);
    expect(error).toMatchObject({ kind: "problem", status: 400, code: "bad_request" });
    expect(error.fieldErrors).toBeUndefined();
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });

  it("403 operator_forbidden → problem（业务结论，不是配置错误，也不写运维告警）", async () => {
    const spy = jest.spyOn(console, "error").mockImplementation(() => undefined);
    stub.respond("changeOwnPassword", { status: 403, body: problemBody(403, "operator_forbidden") });
    const error = await changePassword(FIXTURE_IDS.accountId, { currentPassword: "a", newPassword: "b", clientIp: "203.0.113.7" }, operator).catch((e) => e);
    expect(error).toMatchObject({ kind: "problem", status: 403, code: "operator_forbidden" });
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });

  it("verify 的 401 session_invalid 带 reason: terminated → UamError.reason 为 terminated（被管理员强制下线）", async () => {
    stub.respond("verifySession", { status: 401, body: problemBody(401, "session_invalid", { reason: "terminated" }) });
    await expect(verifySession("s")).rejects.toMatchObject({ kind: "problem", code: "session_invalid", reason: "terminated" });
  });

  it("verify 的 401 session_invalid 不带 reason → UamError.reason 缺省（到期 / 退出 / 被取代 / 用户被停用）", async () => {
    stub.respond("verifySession", { status: 401, body: problemBody(401, "session_invalid") });
    const error = await verifySession("s").catch((e) => e);
    expect(error).toBeInstanceOf(UamError);
    expect(error.reason).toBeUndefined();
  });

  it.each([["expired"], [""], [42], [null], [["terminated"]], ["TERMINATED"]])(
    "reason 取值 %j 不是字面量 terminated → 忽略（不信任契约之外的值）",
    async (reason) => {
      stub.respond("verifySession", { status: 401, body: problemBody(401, "session_invalid", { reason }) });
      const error = await verifySession("s").catch((e) => e);
      expect(error).toMatchObject({ kind: "problem", code: "session_invalid" });
      expect(error.reason).toBeUndefined();
      stub.violations.length = 0; // 故意构造了契约之外的 reason
    },
  );

  it("reason 只对 session_invalid 有意义：其它问题码上出现也不采信", async () => {
    stub.respond("login", { status: 401, body: problemBody(401, "invalid_credentials", { reason: "terminated" }) });
    const error = await uamRequest({ method: "POST", path: "/v1/auth/login", body: { accountName: "a", password: "b", clientIp: "203.0.113.1" } }).catch((e) => e);
    expect(error).toMatchObject({ kind: "problem", code: "invalid_credentials" });
    expect(error.reason).toBeUndefined();
    stub.violations.length = 0;
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
    await expect(listUsers({ query: "Li", page: 2 }, operator)).resolves.toEqual(body);
    expect(stub.calls[0].path).toBe("/v1/users");
    expect(stub.calls[0].query.get("query")).toBe("Li");
    expect(stub.calls[0].query.get("page")).toBe("2");
    expect(stub.calls[0].query.has("size")).toBe(false);
  });

  it("listUsers：无参数 / 空搜索词时不带查询参数；带服务密钥与操作人请求头（US13：读操作也要求操作人，便于审计）", async () => {
    stub.respond("listUsers", { status: 200, body: { items: [], page: 1, size: 20, total: 0 } });
    await listUsers({}, operator);
    await listUsers({ query: "" }, operator);
    for (const call of stub.calls) {
      expect([...call.query.keys()]).toEqual([]);
      expect(call.headers.get("authorization")).toBeNull();
      expect(call.headers.get("x-operator-user-id")).toBe(operator.userId);
      expect(call.headers.get("x-operator-account-id")).toBe(operator.accountId);
      expect(call.headers.get("x-operator-session-ref")).toBe(operator.sessionRef);
    }
  });

  it("listUsers：size 可指定", async () => {
    stub.respond("listUsers", { status: 200, body: { items: [], page: 1, size: 50, total: 0 } });
    await listUsers({ size: 50 }, operator);
    expect(stub.calls[0].query.get("size")).toBe("50");
  });

  it("listUsers：UAM 不可用 → UamError(unavailable)", async () => {
    stub.respond("listUsers", { networkError: true });
    await expect(listUsers({}, operator)).rejects.toMatchObject({ kind: "unavailable" });
  });

  it("listUsers：类型层面不能不带操作人调用（US13：类型系统必须拒绝「页面不先确认会话就读数据」）", async () => {
    stub.respond("listUsers", { status: 200, body: { items: [], page: 1, size: 20, total: 0 } });
    // @ts-expect-error 第二个参数（操作人）是必填的
    await listUsers({});
  });

  it("getUser：取详情（含全部账号），路径参数做编码", async () => {
    const body = { ...summary, accounts: [{ id: FIXTURE_IDS.accountId, loginName: "zhangsan", kind: "PASSWORD", status: "ACTIVE", createdAt: "2026-10-01T08:30:00Z" }] };
    delete (body as Record<string, unknown>).accountCount;
    stub.respond("getUser", { status: 200, body });
    await expect(getUser(FIXTURE_IDS.userId, operator)).resolves.toEqual(body);
    expect(stub.calls[0].path).toBe(`/v1/users/${FIXTURE_IDS.userId}`);
    expect(stub.calls[0].headers.get("x-operator-user-id")).toBe(operator.userId);
  });

  it("getUser：404 user_not_found → UamError(problem, user_not_found)", async () => {
    stub.respond("getUser", { status: 404, body: problemBody(404, "user_not_found") });
    await expect(getUser(FIXTURE_IDS.userId, operator)).rejects.toMatchObject({ kind: "problem", status: 404, code: "user_not_found" });
  });

  it("getUser：userId 里的特殊字符不能改变请求路径", async () => {
    const spy = jest.spyOn(global, "fetch").mockResolvedValue(new Response(JSON.stringify(problemBody(404, "user_not_found")), { status: 404 }));
    await getUser("../sessions?x=1", operator).catch(() => undefined);
    expect(String(spy.mock.calls[0][0])).toContain("/v1/users/..%2Fsessions%3Fx%3D1");
    spy.mockRestore();
  });

  it("getUser：类型层面不能不带操作人调用（US13）", async () => {
    stub.respond("getUser", { status: 404, body: problemBody(404, "user_not_found") });
    // @ts-expect-error 第二个参数（操作人）是必填的
    await getUser(FIXTURE_IDS.userId).catch(() => undefined);
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

  it("listOnlineUsers：页码映射为 page、size 可指定；返回 UAM 的分页数据；带服务密钥与操作人请求头", async () => {
    const body = { items: [online], page: 2, size: 50, total: 51 };
    stub.respond("listOnlineUsers", { status: 200, body });
    await expect(listOnlineUsers({ page: 2, size: 50 }, operator)).resolves.toEqual(body);
    const call = stub.calls[0];
    expect(call.path).toBe("/v1/online-users");
    expect(call.query.get("page")).toBe("2");
    expect(call.query.get("size")).toBe("50");
    expect(call.headers.get("authorization")).toBeNull();
    expect(call.headers.get("x-operator-user-id")).toBe(operator.userId);
  });

  it("listOnlineUsers：无参数时不带查询参数", async () => {
    stub.respond("listOnlineUsers", { status: 200, body: { items: [], page: 1, size: 20, total: 0 } });
    await listOnlineUsers({}, operator);
    expect([...stub.calls[0].query.keys()]).toEqual([]);
  });

  it("listOnlineUsers：UAM 不可用 → UamError(unavailable)", async () => {
    stub.respond("listOnlineUsers", { networkError: true });
    await expect(listOnlineUsers({}, operator)).rejects.toMatchObject({ kind: "unavailable" });
  });

  it("listOnlineUsers：类型层面不能不带操作人调用（US13）", async () => {
    stub.respond("listOnlineUsers", { status: 200, body: { items: [], page: 1, size: 20, total: 0 } });
    // @ts-expect-error 第二个参数（操作人）是必填的
    await listOnlineUsers({});
  });

  it("listUserSessions：带操作人请求头（UAM 据会话引用算 current），返回会话数组", async () => {
    stub.respond("listUserSessions", { status: 200, body: { items: [session] } });
    await expect(listUserSessions(FIXTURE_IDS.userId, operator)).resolves.toEqual([session]);
    const call = stub.calls[0];
    expect(call.path).toBe(`/v1/users/${FIXTURE_IDS.userId}/sessions`);
    expect(call.headers.get("authorization")).toBeNull();
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
  ] as const)("%s（%s）→ %s", (kind, mode, message) => {
    expect(uamErrorMessage(new UamError(kind, "x"), mode)).toBe(message);
  });

  it("bad_request 没有专门文案：读提示服务暂不可用，写提示操作未生效（不是输入有误）", () => {
    expect(uamErrorMessage(problem("bad_request"), "read")).toBe("服务暂不可用，请稍后重试");
    expect(uamErrorMessage(problem("bad_request"), "write")).toBe("操作未生效");
  });

  it("operator_forbidden（改密越权，Admin Center 只传操作人自己的账号，出现即为缺陷）按「操作未生效」处理", () => {
    expect(uamErrorMessage(problem("operator_forbidden"), "write")).toBe("操作未生效");
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
    expect(call.headers.get("authorization")).toBeNull();
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
    expect(sent.headers.get("authorization")).toBeNull();
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

describe("terminateSession（T132）", () => {
  const ref = "5d0c8f3a-1b2c-4d3e-8f4a-0123456789ab";

  it("DELETE /v1/sessions/{ref}：带服务密钥与操作人请求头，没有请求体；204 无返回值", async () => {
    stub.respond("terminateSession", { status: 204 });
    await expect(terminateSession(ref, operator)).resolves.toBeUndefined();
    const [sent] = stub.calls;
    expect(sent.method).toBe("DELETE");
    expect(sent.path).toBe(`/v1/sessions/${ref}`);
    expect(sent.headers.get("authorization")).toBeNull();
    expect(sent.headers.get("x-operator-user-id")).toBe(operator.userId);
    expect(sent.headers.get("x-operator-account-id")).toBe(operator.accountId);
    expect(sent.headers.get("x-operator-session-ref")).toBe(operator.sessionRef);
    expect(sent.body).toBeUndefined();
  });

  it("409 与 404 抛出带问题码的 UamError", async () => {
    stub.respond("terminateSession", { status: 409, body: problemBody(409, "cannot_terminate_own_session") });
    await expect(terminateSession(ref, operator)).rejects.toMatchObject({ kind: "problem", code: "cannot_terminate_own_session", status: 409 });
    stub.respond("terminateSession", { status: 404, body: problemBody(404, "session_not_found") });
    await expect(terminateSession(ref, operator)).rejects.toMatchObject({ kind: "problem", code: "session_not_found", status: 404 });
  });

  it("会话引用里的特殊字符不能改变请求路径", async () => {
    const spy = jest.spyOn(global, "fetch").mockResolvedValue(new Response(JSON.stringify(problemBody(404, "session_not_found")), { status: 404 }));
    await terminateSession("../x?y=1", operator).catch(() => undefined);
    expect(String(spy.mock.calls[0][0])).toContain("/v1/sessions/..%2Fx%3Fy%3D1");
    spy.mockRestore();
  });
});

describe("logout", () => {
  it("POST /v1/auth/logout：只带服务密钥（无操作人请求头）与 { sessionId }；204 无返回值", async () => {
    stub.respond("logout", { status: 204 });
    await expect(logout("sid-123")).resolves.toBeUndefined();
    const [sent] = stub.calls;
    expect(sent.method).toBe("POST");
    expect(sent.path).toBe("/v1/auth/logout");
    expect(sent.headers.get("authorization")).toBeNull();
    expect(sent.headers.get("x-operator-user-id")).toBeNull();
    expect(sent.body).toEqual({ sessionId: "sid-123" });
  });

  it("UAM 不可用时抛 UamError，信息不含会话标识", async () => {
    stub.respond("logout", { status: 503, body: undefined });
    const unavailable = await logout("sid-secret").catch((e) => e);
    stub.violations.length = 0; // 契约没声明 5xx：这里只测归类，清掉桩记录的违约
    expect(unavailable).toMatchObject({ kind: "unavailable" });
    expect(unavailable.message).not.toContain("sid-secret");
  });
});

describe("changePassword", () => {
  const request = { currentPassword: "Old-Secret-pass1", newPassword: "New-Secret-pass2", clientIp: "203.0.113.7" };

  it("POST /v1/accounts/{accountId}/password：带服务密钥、操作人请求头与请求体；204 无返回值", async () => {
    stub.respond("changeOwnPassword", { status: 204 });
    await expect(changePassword(FIXTURE_IDS.accountId, request, operator)).resolves.toBeUndefined();
    const [sent] = stub.calls;
    expect(sent.method).toBe("POST");
    expect(sent.path).toBe(`/v1/accounts/${FIXTURE_IDS.accountId}/password`);
    expect(sent.headers.get("authorization")).toBeNull();
    expect(sent.headers.get("x-operator-user-id")).toBe(operator.userId);
    expect(sent.headers.get("x-operator-account-id")).toBe(operator.accountId);
    expect(sent.headers.get("x-operator-session-ref")).toBe(operator.sessionRef);
    expect(sent.body).toEqual(request);
  });

  it("422 / 429 抛出带问题码的 UamError（含 Retry-After），错误信息不含密码", async () => {
    stub.respond("changeOwnPassword", { status: 422, body: problemBody(422, "current_password_incorrect") });
    const wrong = await changePassword(FIXTURE_IDS.accountId, request, operator).catch((e: unknown) => e);
    expect(wrong).toBeInstanceOf(UamError);
    expect(wrong).toMatchObject({ kind: "problem", status: 422, code: "current_password_incorrect" });

    stub.respond("changeOwnPassword", { status: 429, body: problemBody(429, "throttled"), headers: { "Retry-After": "30" } });
    const slow = await changePassword(FIXTURE_IDS.accountId, request, operator).catch((e: unknown) => e);
    expect(slow).toMatchObject({ kind: "problem", status: 429, code: "throttled", retryAfter: 30 });

    for (const error of [wrong, slow]) {
      expect(JSON.stringify(error)).not.toContain(request.currentPassword);
      expect(String((error as Error).message)).not.toContain(request.newPassword);
    }
  });
});
