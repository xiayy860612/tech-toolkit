/**
 * @jest-environment node
 */
import { cookies } from "next/headers";
import { NextRequest } from "next/server";
import * as route from "@/app/logout/route";
import { POST } from "@/app/logout/route";
import { createUamStub, problemBody, UAM_STUB_BASE_URL, UAM_STUB_SERVICE_KEY } from "../../support/uam-stub";

jest.mock("next/headers", () => ({ headers: jest.fn().mockRejectedValue(new Error("outside")), cookies: jest.fn() }));

const stub = createUamStub();
const saved = { ...process.env };
const SESSION_ID = "sid-secret-value-0123456789abcdef";

const SAME_ORIGIN = { host: "admin.test", origin: "http://admin.test", "sec-fetch-site": "same-origin" };

function setCookie(value: string | undefined) {
  jest.mocked(cookies).mockResolvedValue({ get: (name: string) => (name === "asid" && value ? { name, value } : undefined) } as never);
}

function call(headers: Record<string, string> = SAME_ORIGIN) {
  return POST(new NextRequest("http://localhost:3000/logout", { method: "POST", headers }));
}

const location = (res: Response) => new URL(res.headers.get("location")!, "http://x");

function expectCookieCleared(res: Response) {
  const setCookieHeader = res.headers.get("set-cookie")!;
  expect(setCookieHeader).toMatch(/^asid=;/);
  expect(setCookieHeader).toMatch(/Max-Age=0/i);
  expect(setCookieHeader).toMatch(/HttpOnly/i);
  expect(setCookieHeader).toMatch(/Secure/i);
  expect(setCookieHeader).toMatch(/SameSite=strict/i);
  expect(setCookieHeader).toMatch(/Path=\//);
  expect(setCookieHeader).not.toMatch(/Domain/i);
}

let warn: jest.SpyInstance;
let error: jest.SpyInstance;

beforeAll(() => stub.listen());
afterAll(() => stub.close());
beforeEach(() => {
  process.env.UAM_BASE_URL = UAM_STUB_BASE_URL;
  process.env.UAM_SERVICE_KEY = UAM_STUB_SERVICE_KEY;
  process.env.UAM_TIMEOUT_MS = "1500";
  process.env.SESSION_COOKIE_NAME = "asid";
  setCookie(SESSION_ID);
  warn = jest.spyOn(console, "warn").mockImplementation(() => undefined);
  error = jest.spyOn(console, "error").mockImplementation(() => undefined);
});
afterEach(() => {
  expect(stub.violations).toEqual([]);
  stub.reset();
  process.env = { ...saved };
  warn.mockRestore();
  error.mockRestore();
});

describe("POST /logout", () => {
  it("调 UAM 退出（带会话标识）、清 cookie、303 到 /login", async () => {
    stub.respond("logout", { status: 204 });
    const res = await call();

    expect(stub.calls).toHaveLength(1);
    expect(stub.calls[0].body).toEqual({ sessionId: SESSION_ID });
    expect(stub.calls[0].headers.get("authorization")).toBe(`Bearer ${UAM_STUB_SERVICE_KEY}`);
    expect(res.status).toBe(303);
    // 相对地址：容器内 request.url 的主机可能是 0.0.0.0，不能拿它拼绝对地址
    expect(res.headers.get("location")).toBe("/login");
    expectCookieCleared(res);
    expect(res.headers.get("cache-control")).toBe("no-store");
  });

  it("UAM 不可用：仍清 cookie 并回 /login（浏览器侧必须能退出），记警告但不含会话标识与密钥", async () => {
    stub.respond("logout", { status: 503, body: undefined });
    const res = await call();
    stub.violations.length = 0; // 契约没声明 5xx：这里只测归类，清掉桩记录的违约

    expect(res.status).toBe(303);
    expect(location(res).pathname).toBe("/login");
    expectCookieCleared(res);
    expect(warn).toHaveBeenCalledTimes(1);
    const logged = JSON.stringify(warn.mock.calls);
    expect(logged).not.toContain(SESSION_ID);
    expect(logged).not.toContain(UAM_STUB_SERVICE_KEY);
  });

  it("UAM 连接失败 / 超时：同样清 cookie 回 /login", async () => {
    stub.respond("logout", { networkError: true });
    const res = await call();
    expect(res.status).toBe(303);
    expectCookieCleared(res);
    expect(warn).toHaveBeenCalledTimes(1);
  });

  it("UAM 拒绝了 Admin Center（服务密钥 / 权限配置错误）：仍清 cookie 回 /login，并写运维告警", async () => {
    stub.respond("logout", { status: 403, body: problemBody(403, "client_forbidden") });
    const res = await call();
    expect(res.status).toBe(303);
    expectCookieCleared(res);
    expect(error).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(error.mock.calls)).not.toContain(SESSION_ID);
  });

  it("本来就没有 cookie：不调 UAM，直接回 /login", async () => {
    setCookie(undefined);
    const res = await call();
    expect(stub.calls).toHaveLength(0);
    expect(res.status).toBe(303);
    expect(location(res).pathname).toBe("/login");
  });

  it("会话在 UAM 里本来就不存在：UAM 同样 204，行为不变（幂等）", async () => {
    stub.respond("logout", { status: 204 });
    const first = await call();
    const second = await call();
    expect(first.status).toBe(303);
    expect(second.status).toBe(303);
    expectCookieCleared(second);
  });

  it.each([
    ["Origin 是别的站点", { host: "admin.test", origin: "http://evil.example" }],
    ["Sec-Fetch-Site 是 cross-site", { host: "admin.test", "sec-fetch-site": "cross-site" }],
    ["既没有 Origin 也没有 Sec-Fetch-Site", { host: "admin.test" }],
  ])("同源校验失败（%s）：403，不调 UAM，不清 cookie（FR-033）", async (_name, headers) => {
    const res = await call(headers);
    expect(res.status).toBe(403);
    expect(res.headers.get("set-cookie")).toBeNull();
    expect(res.headers.get("location")).toBeNull();
    expect(stub.calls).toHaveLength(0);
  });

  it("非 UAM 错误是程序问题（如配置缺失）：不吞掉", async () => {
    delete process.env.UAM_SERVICE_KEY;
    await expect(call()).rejects.toThrow();
  });

  it("只接受 POST：不导出 GET 等其它方法（Next 对未导出的方法回 405），退出不能被链接或图片触发", () => {
    expect(Object.keys(route).sort()).toEqual(["POST"]);
  });
});
