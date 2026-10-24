/**
 * @jest-environment node
 *
 * T176（US12，FR-008、FR-042 ~ FR-044）：UAM 拒绝了 Admin Center 本身（401 `client_unauthenticated` /
 * 403 `client_forbidden`，通常是服务密钥配置错误）时，一律按 `unverified` 处理——
 * 不登出用户、不清 cookie、显示「服务暂不可用」（写操作「操作未生效」），并写一条运维告警日志（不含任何机密）。
 * 唯一例外是退出登录：用户必须总能从浏览器侧退出。
 *
 * 这里走真实的 getSession / 路由 / Server Action 与 UAM 桩，不 mock 会话模块。
 */
import { cookies, headers } from "next/headers";
import { NextRequest } from "next/server";
import { changePassword } from "@/app/(shell)/account/password/actions";
import { terminateSession } from "@/app/(shell)/sessions/[userId]/actions";
import { createUser } from "@/app/(shell)/users/new/actions";
import { disableUser, enableUser } from "@/app/(shell)/users/[userId]/actions";
import { login } from "@/app/(auth)/login/actions";
import { POST as logoutRoute } from "@/app/logout/route";
import { GET as expireRoute } from "@/app/session/expire/route";
import { getSession } from "@/lib/session/get-session";
import { createUamStub, FIXTURE_IDS, problemBody, UAM_STUB_BASE_URL, UAM_STUB_SERVICE_KEY, verifyResponseBody } from "../../../support/uam-stub";

jest.mock("next/headers", () => ({ headers: jest.fn(), cookies: jest.fn() }));
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: jest.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));

const stub = createUamStub();
const saved = { ...process.env };

/** 运行期拼出的假密钥：形状像真密钥，但不在仓库里留下可被密钥扫描匹配的字面量。 */
const FAKE_KEY = ["uamk", "_", "FakeKeyOnlyForRedactionTests", "0123456789abcdefghij"].join("");
const SESSION_ID = "sid-cookie-secret-0123456789abcdef";
const PASSWORD = "Sup3r-Secret-pass";
const NEW_PASSWORD = "New-Secret-pass2";
const TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
const TRACEPARENT = `00-${TRACE_ID}-00f067aa0ba902b7-01`;
const IDEMPOTENCY_KEY = "11111111-2222-4333-8444-555555555555";
const SAME_ORIGIN = {
  host: "admin.test",
  origin: "http://admin.test",
  "sec-fetch-site": "same-origin",
  "x-forwarded-for": "203.0.113.7",
  traceparent: TRACEPARENT,
};

const EVENT = "admin-center.uam.misconfigured";

const SCENARIOS = [
  { label: "401 client_unauthenticated", status: 401, code: "client_unauthenticated" },
  { label: "403 client_forbidden", status: 403, code: "client_forbidden" },
] as const;
type Scenario = (typeof SCENARIOS)[number];

const cookieStore = { get: jest.fn(), set: jest.fn(), delete: jest.fn() };
const logs: Record<"log" | "info" | "warn" | "error", jest.SpyInstance> = {} as never;

function setCookie(value: string | undefined) {
  cookieStore.get.mockImplementation((name: string) => (value !== undefined && name === "asid" ? { name, value } : undefined));
}

/** 让 UAM 对「所有」操作都拒绝 Admin Center（模拟服务密钥错误 / 权限被收回）。 */
function rejectEverything({ status, code }: Scenario) {
  for (const op of ["verifySession", "login", "logout", "createUser", "disableUser", "enableUser", "terminateSession", "changeOwnPassword"]) {
    stub.respond(op, { status, body: problemBody(status, code) });
  }
}

/** 会话本身有效，只有「操作」被拒绝（权限被收回但读仍可用的情形）。 */
function sessionValidButOperationRejected(operationId: string, { status, code }: Scenario) {
  stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
  stub.respond(operationId, { status, body: problemBody(status, code) });
}

const errorLines = (): string[] => logs.error.mock.calls.map((call) => String(call[0]));
const allLogged = () => JSON.stringify(Object.values(logs).flatMap((l) => l.mock.calls));
const parsed = (line: string) => JSON.parse(line) as Record<string, unknown>;

function newUserForm() {
  const fd = new FormData();
  fd.set("userName", "王五");
  fd.set("accountName", "wangwu");
  fd.set("password", PASSWORD);
  fd.set("idempotencyKey", IDEMPOTENCY_KEY);
  return fd;
}

function passwordForm() {
  const fd = new FormData();
  fd.set("currentPassword", PASSWORD);
  fd.set("newPassword", NEW_PASSWORD);
  fd.set("confirmPassword", NEW_PASSWORD);
  return fd;
}

function loginForm() {
  const fd = new FormData();
  fd.set("accountName", "zhangsan");
  fd.set("password", PASSWORD);
  return fd;
}

beforeAll(() => stub.listen());
afterAll(() => stub.close());
beforeEach(() => {
  process.env.UAM_BASE_URL = UAM_STUB_BASE_URL;
  process.env.UAM_SERVICE_KEY = UAM_STUB_SERVICE_KEY;
  process.env.UAM_TIMEOUT_MS = "1500";
  process.env.SESSION_COOKIE_NAME = "asid";
  process.env.TRUSTED_PROXY_HOPS = "1";
  jest.mocked(cookies).mockResolvedValue(cookieStore as never);
  jest.mocked(headers).mockResolvedValue(new Headers(SAME_ORIGIN) as never);
  setCookie(SESSION_ID);
  for (const m of ["log", "info", "warn", "error"] as const) logs[m] = jest.spyOn(console, m).mockImplementation(() => undefined);
});
afterEach(() => {
  expect(stub.violations).toEqual([]);
  Object.values(logs).forEach((l) => l.mockRestore());
  stub.reset();
  cookieStore.get.mockReset();
  cookieStore.set.mockReset();
  cookieStore.delete.mockReset();
  jest.clearAllMocks();
  jest.restoreAllMocks();
  process.env = { ...saved };
});

describe.each(SCENARIOS)("UAM 拒绝 Admin Center（$label）", (scenario) => {
  describe("getSession", () => {
    it("按 unverified 处理（不是 anonymous），cookie 原封不动", async () => {
      rejectEverything(scenario);
      expect(await getSession()).toEqual({ status: "unverified" });
      expect(cookieStore.set).not.toHaveBeenCalled();
      expect(cookieStore.delete).not.toHaveBeenCalled();
    });

    it("写一条运维告警：事件名、UAM 错误码、HTTP 状态、操作上下文、trace id、时间戳", async () => {
      rejectEverything(scenario);
      await getSession();
      const lines = errorLines();
      expect(lines).toHaveLength(1);
      expect(lines[0]).not.toContain("\n");
      expect(parsed(lines[0])).toMatchObject({
        level: "error",
        event: EVENT,
        code: scenario.code,
        status: scenario.status,
        operation: "POST /v1/auth/sessions/verify",
        traceId: TRACE_ID,
      });
      expect(Number.isNaN(Date.parse(String(parsed(lines[0]).time)))).toBe(false);
    });
  });

  describe("GET /session/expire", () => {
    it("UAM 拒绝了 Admin Center：不清 cookie，回受保护页面", async () => {
      rejectEverything(scenario);
      const res = await expireRoute(new NextRequest("http://localhost:3000/session/expire?next=%2Fusers%2Fabc"));
      expect(res.status).toBe(303);
      expect(new URL(res.headers.get("location")!, "http://x").pathname).toBe("/users/abc");
      expect(res.headers.get("set-cookie")).toBeNull();
      expect(cookieStore.set).not.toHaveBeenCalled();
      expect(cookieStore.delete).not.toHaveBeenCalled();
    });
  });

  describe("POST /logout（唯一例外）", () => {
    it("仍清除本地 cookie 并回 /login，同时写告警", async () => {
      rejectEverything(scenario);
      const res = await logoutRoute(new NextRequest("http://localhost:3000/logout", { method: "POST", headers: SAME_ORIGIN }));
      expect(res.status).toBe(303);
      expect(new URL(res.headers.get("location")!, "http://x").pathname).toBe("/login");
      const setCookie = res.headers.get("set-cookie")!;
      expect(setCookie).toMatch(/^asid=;/);
      expect(setCookie).toMatch(/Max-Age=0/i);
      expect(errorLines().map((l) => parsed(l).event)).toEqual([EVENT]);
    });
  });

  describe("登录", () => {
    it("提示「服务暂不可用」，不是账号名或密码错误；不写 cookie", async () => {
      rejectEverything(scenario);
      const state = await login({}, loginForm());
      expect(state.formError).toBe("服务暂不可用，请稍后重试");
      expect(state.formError).not.toMatch(/密码错误|账号名/);
      expect(state.fieldErrors).toBeUndefined();
      expect(cookieStore.set).not.toHaveBeenCalled();
      expect(errorLines().map((l) => parsed(l).operation)).toEqual(["POST /v1/auth/login"]);
    });
  });

  describe("写操作：「操作未生效」，绝不显示成功，cookie 不动", () => {
    it("新建用户", async () => {
      sessionValidButOperationRejected("createUser", scenario);
      const state = await createUser({}, newUserForm());
      expect(state.formError).toBe("操作未生效");
      expect(state.idempotencyKey).toBe(IDEMPOTENCY_KEY); // 可能已生效的写：保持原幂等键
      expect(errorLines().map((l) => parsed(l).operation)).toEqual(["POST /v1/users"]);
    });

    it("停用用户", async () => {
      sessionValidButOperationRejected("disableUser", scenario);
      expect(await disableUser(FIXTURE_IDS.userId)).toEqual({ ok: false, message: "操作未生效" });
      expect(errorLines().map((l) => parsed(l).operation)).toEqual(["POST /v1/users/:id/disable"]);
    });

    it("启用用户", async () => {
      sessionValidButOperationRejected("enableUser", scenario);
      expect(await enableUser(FIXTURE_IDS.userId)).toEqual({ ok: false, message: "操作未生效" });
      expect(errorLines().map((l) => parsed(l).operation)).toEqual(["POST /v1/users/:id/enable"]);
    });

    it("强制下线会话", async () => {
      sessionValidButOperationRejected("terminateSession", scenario);
      const other = "99999999-9999-4999-8999-999999999999";
      expect(await terminateSession(FIXTURE_IDS.userId, other)).toEqual({ ok: false, message: "操作未生效" });
      expect(errorLines().map((l) => parsed(l).operation)).toEqual(["DELETE /v1/sessions/:id"]);
    });

    it("修改密码", async () => {
      sessionValidButOperationRejected("changeOwnPassword", scenario);
      expect(await changePassword({}, passwordForm())).toEqual({ formError: "操作未生效" });
      expect(errorLines().map((l) => parsed(l).operation)).toEqual(["POST /v1/accounts/:id/password"]);
    });

    it("会话也无法确认（服务密钥整体错误）：同样「操作未生效」，且不动 cookie", async () => {
      rejectEverything(scenario);
      expect(await disableUser(FIXTURE_IDS.userId)).toEqual({ ok: false, message: "操作未生效" });
      expect(await createUser({}, newUserForm())).toMatchObject({ formError: "操作未生效" });
      expect(await changePassword({}, passwordForm())).toEqual({ formError: "操作未生效" });
      expect(cookieStore.set).not.toHaveBeenCalled();
      expect(cookieStore.delete).not.toHaveBeenCalled();
    });
  });
});

describe("告警日志不含机密", () => {
  it("服务密钥、会话标识、cookie、密码、请求体都不进日志", async () => {
    process.env.UAM_SERVICE_KEY = FAKE_KEY; // 与桩的密钥不一致 → 桩回 401 client_unauthenticated
    expect(await getSession()).toEqual({ status: "unverified" });
    await login({}, loginForm());
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    process.env.UAM_SERVICE_KEY = FAKE_KEY;
    await createUser({}, newUserForm());
    await logoutRoute(new NextRequest("http://localhost:3000/logout", { method: "POST", headers: SAME_ORIGIN }));

    expect(errorLines().length).toBeGreaterThan(0);
    const logged = allLogged();
    for (const secret of [FAKE_KEY, SESSION_ID, PASSWORD, NEW_PASSWORD, IDEMPOTENCY_KEY, "Bearer", "zhangsan", "wangwu", UAM_STUB_SERVICE_KEY]) {
      expect(logged).not.toContain(secret);
    }
  });

  it("告警只有固定字段（没有错误对象、请求头、请求体）", async () => {
    process.env.UAM_SERVICE_KEY = FAKE_KEY;
    await getSession();
    const line = parsed(errorLines()[0]);
    expect(Object.keys(line).sort()).toEqual(["code", "event", "level", "operation", "status", "time", "traceId"]);
    expect(logs.error.mock.calls[0]).toHaveLength(1);
  });
});

describe("告警限流：洪水般的请求不会无限写日志", () => {
  it("同一错误码 + 同一操作在窗口内只写一条", async () => {
    rejectEverything(SCENARIOS[0]);
    for (let i = 0; i < 50; i++) await getSession();
    expect(errorLines()).toHaveLength(1);
  });

  it("窗口过后再写一条，并带上被抑制的次数；之后重新计数", async () => {
    rejectEverything(SCENARIOS[0]);
    const now = jest.spyOn(Date, "now");
    const t0 = 1_760_000_000_000;
    now.mockReturnValue(t0);
    for (let i = 0; i < 6; i++) await getSession(); // 1 条 + 5 次被抑制
    expect(errorLines()).toHaveLength(1);
    expect(parsed(errorLines()[0]).suppressed).toBeUndefined();

    now.mockReturnValue(t0 + 30_000); // 窗口内
    await getSession();
    expect(errorLines()).toHaveLength(1);

    now.mockReturnValue(t0 + 61_000); // 窗口过后
    await getSession();
    expect(errorLines()).toHaveLength(2);
    expect(parsed(errorLines()[1]).suppressed).toBe(6);

    now.mockReturnValue(t0 + 130_000);
    await getSession();
    expect(errorLines()).toHaveLength(3);
    expect(parsed(errorLines()[2]).suppressed).toBeUndefined();
  });

  it("不同错误码、不同操作各自计数（不会互相吞掉）", async () => {
    rejectEverything(SCENARIOS[0]);
    await getSession();
    rejectEverything(SCENARIOS[1]);
    await getSession();
    await login({}, loginForm());
    expect(errorLines().map((l) => [parsed(l).code, parsed(l).operation])).toEqual([
      ["client_unauthenticated", "POST /v1/auth/sessions/verify"],
      ["client_forbidden", "POST /v1/auth/sessions/verify"],
      ["client_forbidden", "POST /v1/auth/login"],
    ]);
  });
});

describe("非「配置错误」的失败不写这条告警", () => {
  it("401 session_invalid 是用户侧结论，不是 Admin Center 配置问题", async () => {
    stub.respond("verifySession", { status: 401, body: problemBody(401, "session_invalid") });
    expect(await getSession()).toEqual({ status: "anonymous", hadCookie: true });
    expect(errorLines()).toHaveLength(0);
  });

  it("UAM 不可用（连接失败）：unverified，但不是密钥告警", async () => {
    stub.respond("verifySession", { networkError: true });
    expect(await getSession()).toEqual({ status: "unverified" });
    expect(errorLines()).toHaveLength(0);
  });
});
