/**
 * @jest-environment node
 */
import { cookies } from "next/headers";
import {
  createUamStub,
  problemBody,
  UAM_STUB_BASE_URL,
  UAM_STUB_SERVICE_KEY,
  verifyResponseBody,
} from "../../../support/uam-stub";

jest.mock("next/headers", () => ({ cookies: jest.fn(), headers: jest.fn().mockRejectedValue(new Error("no scope")) }));
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

type State = { status: string; hadCookie?: boolean; session?: { user: { name: string } } };
let getSession: () => Promise<State>;
beforeAll(async () => {
  getSession = (await import("@/lib/session/get-session")).getSession as unknown as typeof getSession;
});

beforeAll(() => stub.listen());
afterAll(() => stub.close());
beforeEach(() => {
  mockCacheResets.forEach((reset) => reset());
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
