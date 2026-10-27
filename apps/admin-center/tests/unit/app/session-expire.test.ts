/**
 * @jest-environment node
 */
import { NextRequest } from "next/server";
import { GET } from "@/app/session/expire/route";
import { getSession } from "@/lib/session/get-session";

jest.mock("@/lib/session/get-session", () => ({ getSession: jest.fn() }));

const saved = { ...process.env };

beforeEach(() => {
  process.env.UAM_BASE_URL = "http://uam.test";
  process.env.SESSION_COOKIE_NAME = "asid";
});
afterEach(() => {
  process.env = { ...saved };
  jest.resetAllMocks();
});

const call = (search = "") => GET(new NextRequest(`http://localhost:3000/session/expire${search}`));

describe("GET /session/expire", () => {
  it("重新 verify 仍无效：清除 cookie，303 到 /login 并带「会话已结束」原因", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true });
    const res = await call();
    expect(res.status).toBe(303);
    // 相对地址：容器内 request.url 的主机可能是 0.0.0.0，不能拿它拼绝对地址
    const location = new URL(res.headers.get("location")!, "http://x");
    expect(location.pathname).toBe("/login");
    expect(location.searchParams.get("reason")).toBe("session_ended");
    const setCookie = res.headers.get("set-cookie")!;
    expect(setCookie).toMatch(/^asid=;/);
    expect(setCookie).toMatch(/Max-Age=0/i);
    expect(setCookie).toMatch(/HttpOnly/i);
    expect(setCookie).toMatch(/Secure/i);
    expect(setCookie).toMatch(/SameSite=strict/i);
    expect(setCookie).toMatch(/Path=\//);
    expect(setCookie).not.toMatch(/Domain/i);
  });

  it("verify 说被管理员强制下线（reason=terminated）：清除 cookie，303 到 /login?reason=session_terminated", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true, reason: "terminated" });
    const res = await call();
    expect(res.status).toBe(303);
    const location = new URL(res.headers.get("location")!, "http://x");
    expect(location.pathname).toBe("/login");
    expect(location.searchParams.get("reason")).toBe("session_terminated");
    expect(res.headers.get("location")).toMatch(/^\/login\?/); // 相对地址
    expect(res.headers.get("set-cookie")).toMatch(/^asid=;.*Max-Age=0/i);
  });

  it("被强制下线且带 next：/login?reason=session_terminated&next=<原路径>", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true, reason: "terminated" });
    const res = await call(`?next=${encodeURIComponent("/users/abc?x=1")}`);
    const location = new URL(res.headers.get("location")!, "http://x");
    expect(location.searchParams.get("reason")).toBe("session_terminated");
    expect(location.searchParams.get("next")).toBe("/users/abc?x=1");
  });

  it("anonymous 没有 reason（到期 / 退出 / 被取代 / 用户被停用）：仍是 session_ended", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true });
    const res = await call();
    expect(new URL(res.headers.get("location")!, "http://x").searchParams.get("reason")).toBe("session_ended");
  });

  it("本来就没有 cookie：直接去 /login，不带「会话已结束」", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: false });
    const res = await call();
    expect(res.status).toBe(303);
    const location = new URL(res.headers.get("location")!, "http://x");
    expect(location.pathname).toBe("/login");
    expect(location.searchParams.has("reason")).toBe(false);
  });

  it("unverified（UAM 不可用）：不清 cookie，回到受保护页面看到「服务暂不可用」", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "unverified" });
    const res = await call();
    expect(res.status).toBe(303);
    expect(res.headers.get("set-cookie")).toBeNull();
    expect(new URL(res.headers.get("location")!, "http://x").pathname).toBe("/users");
  });

  it("会话其实又有效了：不清 cookie，回 /users", async () => {
    jest.mocked(getSession).mockResolvedValue({
      status: "authenticated",
      session: {} as never,
    });
    const res = await call();
    expect(res.status).toBe(303);
    expect(res.headers.get("set-cookie")).toBeNull();
    expect(new URL(res.headers.get("location")!, "http://x").pathname).toBe("/users");
  });

  it("带 next：失效后去 /login?reason=session_ended&next=<原路径>", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true });
    const res = await call(`?next=${encodeURIComponent("/users/abc?x=1")}`);
    const location = new URL(res.headers.get("location")!, "http://x");
    expect(location.pathname).toBe("/login");
    expect(location.searchParams.get("reason")).toBe("session_ended");
    expect(location.searchParams.get("next")).toBe("/users/abc?x=1");
  });

  it("没有 cookie 且带 next：/login?next=<原路径>", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: false });
    const res = await call(`?next=${encodeURIComponent("/sessions")}`);
    const location = new URL(res.headers.get("location")!, "http://x");
    expect(location.searchParams.get("next")).toBe("/sessions");
    expect(location.searchParams.has("reason")).toBe(false);
  });

  it("next 不是安全的站内路径：忽略，不带 next", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true });
    const res = await call(`?next=${encodeURIComponent("//evil.example")}`);
    expect(new URL(res.headers.get("location")!, "http://x").searchParams.has("next")).toBe(false);
  });

  it("会话仍有效 / UAM 不可用：回到 next 指向的页面（安全时），否则 /users", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "unverified" });
    const res = await call(`?next=${encodeURIComponent("/sessions")}`);
    expect(new URL(res.headers.get("location")!, "http://x").pathname).toBe("/sessions");
  });

  it("响应不可缓存", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "unverified" });
    const res = await call();
    expect(res.headers.get("cache-control")).toBe("no-store");
  });
});
