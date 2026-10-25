/**
 * @jest-environment node
 */
import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import {
  createUamStub,
  FIXTURE_IDS,
  problemBody,
  UAM_STUB_BASE_URL,
  UAM_STUB_SERVICE_KEY,
  verifyResponseBody,
} from "../../../support/uam-stub";

jest.mock("next/headers", () => ({ cookies: jest.fn(), headers: jest.fn().mockRejectedValue(new Error("no scope")) }));
jest.mock("next/navigation", () => ({
  redirect: jest.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));
// 测试环境里的 React.cache 不做去重（那是 RSC 请求作用域才有的行为）：换成按调用记忆的替身，
// 以此断言 getSession 确实被 cache() 包裹、同一请求内只 verify 一次；每个用例前清空记忆，模拟新请求。
const mockCacheResets: Array<() => void> = [];
jest.mock("react", () => ({
  ...jest.requireActual("react"),
  cache: <T extends (...args: never[]) => unknown>(fn: T): T => {
    let memo: { value: unknown } | undefined;
    mockCacheResets.push(() => {
      memo = undefined;
    });
    return ((...args: never[]) => (memo ??= { value: fn(...args) }).value) as T;
  },
}));

const stub = createUamStub();
const saved = { ...process.env };

function setCookie(value: string | undefined) {
  jest.mocked(cookies).mockResolvedValue({
    get: (name: string) => (value !== undefined && name === "asid" ? { name, value } : undefined),
  } as never);
}

function setRequestPath(path?: string) {
  jest.mocked(headers).mockResolvedValue(new Headers(path ? { "x-next-path": path } : {}) as never);
}

type State = { status: string; hadCookie?: boolean; reason?: string; session?: { user: { name: string } } };
type Operator = { userId: string; accountId: string; sessionRef: string };
let getSession: () => Promise<State>;
let getOperator: () => Promise<Operator | null>;
let anonymousRedirectTarget: (hadCookie: boolean) => Promise<string>;
let requireOperator: () => Promise<Operator | null>;
beforeAll(async () => {
  const mod = await import("@/lib/session/get-session");
  getSession = mod.getSession as unknown as typeof getSession;
  getOperator = mod.getOperator;
  anonymousRedirectTarget = mod.anonymousRedirectTarget;
  requireOperator = mod.requireOperator as unknown as typeof requireOperator;
});

beforeAll(() => stub.listen());
afterAll(() => stub.close());
beforeEach(() => {
  mockCacheResets.forEach((reset) => reset());
  jest.mocked(headers).mockRejectedValue(new Error("no scope"));
  jest.mocked(redirect).mockClear();
  process.env.UAM_BASE_URL = UAM_STUB_BASE_URL;
  process.env.UAM_SERVICE_KEY = UAM_STUB_SERVICE_KEY;
  process.env.UAM_TIMEOUT_MS = "100";
  process.env.SESSION_COOKIE_NAME = "asid";
});
afterEach(() => {
  expect(stub.violations).toEqual([]);
  stub.reset();
  process.env = { ...saved };
});

describe("getSession 三态", () => {
  it("无 cookie → anonymous，且不调用 UAM", async () => {
    setCookie(undefined);
    expect(await getSession()).toEqual({ status: "anonymous", hadCookie: false });
    expect(stub.calls).toHaveLength(0);
  });

  it("verify 200 → authenticated，带会话数据；会话标识作为 verify 的输入", async () => {
    setCookie("cookie-session-id");
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    const state = await getSession();
    expect(state.status).toBe("authenticated");
    expect(state.session?.user.name).toBe("张三");
    expect(stub.calls[0].body).toEqual({ sessionId: "cookie-session-id" });
  });

  it("401 session_invalid → anonymous（带过 cookie）", async () => {
    setCookie("stale");
    stub.respond("verifySession", { status: 401, body: problemBody(401, "session_invalid") });
    expect(await getSession()).toEqual({ status: "anonymous", hadCookie: true });
  });

  it("401 session_invalid 带 reason: terminated → anonymous 且带 reason（被管理员强制下线）", async () => {
    setCookie("killed");
    stub.respond("verifySession", { status: 401, body: problemBody(401, "session_invalid", { reason: "terminated" }) });
    expect(await getSession()).toEqual({ status: "anonymous", hadCookie: true, reason: "terminated" });
  });

  it("401 session_invalid 带契约之外的 reason → 忽略，状态里没有 reason", async () => {
    setCookie("stale");
    stub.respond("verifySession", { status: 401, body: problemBody(401, "session_invalid", { reason: "whatever" }) });
    const state = (await getSession()) as { reason?: string };
    expect(state).toEqual({ status: "anonymous", hadCookie: true });
    expect("reason" in state).toBe(false);
    stub.violations.length = 0; // 故意构造了契约之外的 reason
  });

  it("超时 → unverified", async () => {
    setCookie("sid");
    stub.respond("verifySession", { status: 200, body: verifyResponseBody(), delayMs: 500 });
    expect(await getSession()).toEqual({ status: "unverified" });
  });

  it("5xx → unverified", async () => {
    setCookie("sid");
    stub.respond("verifySession", { status: 503 });
    expect(await getSession()).toEqual({ status: "unverified" });
    stub.violations.length = 0; // 契约没声明 503
  });

  it("400 bad_request（意外拒绝，不是会话结论）→ unverified：保留 cookie，不当作 anonymous", async () => {
    setCookie("sid");
    stub.respond("verifySession", { status: 400, body: problemBody(400, "bad_request") });
    expect(await getSession()).toEqual({ status: "unverified" });
  });

  it("连接失败 → unverified", async () => {
    setCookie("sid");
    stub.respond("verifySession", { networkError: true });
    expect(await getSession()).toEqual({ status: "unverified" });
  });

  it.each([
    ["401 client_unauthenticated", 401, "client_unauthenticated"],
    ["403 client_forbidden", 403, "client_forbidden"],
  ])("%s（misconfigured）→ unverified，不当作 session_invalid", async (_n, status, code) => {
    setCookie("sid");
    stub.respond("verifySession", { status, body: problemBody(status, code) });
    expect(await getSession()).toEqual({ status: "unverified" });
  });

  it("单个请求内去重：多次调用只 verify 一次", async () => {
    setCookie("sid");
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    const [a, b, c] = await Promise.all([getSession(), getSession(), getSession()]);
    expect(a).toBe(b);
    expect(b).toBe(c);
    expect(stub.calls).toHaveLength(1);
  });

  it("遇到非预期异常（bug）时不吞掉", async () => {
    setCookie("sid");
    delete process.env.UAM_SERVICE_KEY; // 配置错误是程序问题，应抛出而不是静默成 unverified
    await expect(getSession()).rejects.toThrow("UAM_SERVICE_KEY");
  });
});

describe("getOperator", () => {
  it("已登录 → 操作人（用户、账号、会话引用），用于 X-Operator-* 请求头", async () => {
    setCookie("sid");
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    expect(await getOperator()).toEqual({
      userId: FIXTURE_IDS.userId,
      accountId: FIXTURE_IDS.accountId,
      sessionRef: FIXTURE_IDS.sessionRef,
    });
  });

  it("未登录 / 无法确认会话 → null", async () => {
    setCookie(undefined);
    expect(await getOperator()).toBeNull();

    setCookie("sid");
    stub.respond("verifySession", { status: 200, body: verifyResponseBody(), delayMs: 500 });
    expect(await getOperator()).toBeNull();
  });
});

describe("anonymousRedirectTarget（(shell)/layout.tsx 与 requireOperator 共用）", () => {
  it("没带过 cookie → /login，带安全的 next", async () => {
    setRequestPath("/sessions");
    expect(await anonymousRedirectTarget(false)).toBe(`/login?next=${encodeURIComponent("/sessions")}`);
  });

  it("带过 cookie → /session/expire，带安全的 next", async () => {
    setRequestPath("/users/abc?x=1");
    expect(await anonymousRedirectTarget(true)).toBe(`/session/expire?next=${encodeURIComponent("/users/abc?x=1")}`);
  });

  it("原路径缺失或不安全：不带 next", async () => {
    setRequestPath();
    expect(await anonymousRedirectTarget(false)).toBe("/login");
    setRequestPath("//evil.example");
    expect(await anonymousRedirectTarget(true)).toBe("/session/expire");
  });
});

describe("requireOperator（US13：页面级会话守卫，RSC 局部渲染可能跳过外壳布局，见 CVE 复现）", () => {
  it("authenticated → 操作人（用户、账号、会话引用）", async () => {
    setCookie("sid");
    stub.respond("verifySession", { status: 200, body: verifyResponseBody() });
    expect(await requireOperator()).toEqual({
      userId: FIXTURE_IDS.userId,
      accountId: FIXTURE_IDS.accountId,
      sessionRef: FIXTURE_IDS.sessionRef,
    });
    expect(redirect).not.toHaveBeenCalled();
  });

  it("unverified → null，不跳转、不暴露任何数据", async () => {
    setCookie("sid");
    stub.respond("verifySession", { status: 200, body: verifyResponseBody(), delayMs: 500 });
    expect(await requireOperator()).toBeNull();
    expect(redirect).not.toHaveBeenCalled();
  });

  it("anonymous 没有 cookie → 跳转 /login?next=<当前请求路径>，全程不调用 UAM", async () => {
    setCookie(undefined);
    setRequestPath("/users");
    await expect(requireOperator()).rejects.toThrow(`NEXT_REDIRECT:/login?next=${encodeURIComponent("/users")}`);
    expect(stub.calls).toHaveLength(0);
  });

  it("anonymous 带过失效 cookie → 跳转 /session/expire?next=<当前请求路径>", async () => {
    setCookie("stale");
    stub.respond("verifySession", { status: 401, body: problemBody(401, "session_invalid") });
    setRequestPath("/sessions/abc");
    await expect(requireOperator()).rejects.toThrow(`NEXT_REDIRECT:/session/expire?next=${encodeURIComponent("/sessions/abc")}`);
  });
});
