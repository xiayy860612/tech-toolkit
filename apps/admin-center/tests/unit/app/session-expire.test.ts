/**
 * @jest-environment node
 */
import { GET } from "@/app/session/expire/route";
import { getSession } from "@/lib/session/get-session";

jest.mock("@/lib/session/get-session", () => ({ getSession: jest.fn() }));

const saved = { ...process.env };

beforeEach(() => {
  process.env.UAM_BASE_URL = "http://uam.test";
  process.env.UAM_SERVICE_KEY = "test-service-key-not-a-secret";
  process.env.SESSION_COOKIE_NAME = "asid";
});
afterEach(() => {
  process.env = { ...saved };
  jest.resetAllMocks();
});

describe("GET /session/expire", () => {
  it("重新 verify 仍无效：清除 cookie，303 到 /login 并带「会话已结束」原因", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true });
    const res = await GET();
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

  it("本来就没有 cookie：直接去 /login，不带「会话已结束」", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: false });
    const res = await GET();
    expect(res.status).toBe(303);
    const location = new URL(res.headers.get("location")!, "http://x");
    expect(location.pathname).toBe("/login");
    expect(location.searchParams.has("reason")).toBe(false);
  });

  it("unverified（UAM 不可用）：不清 cookie，回到受保护页面看到「服务暂不可用」", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "unverified" });
    const res = await GET();
    expect(res.status).toBe(303);
    expect(res.headers.get("set-cookie")).toBeNull();
    expect(new URL(res.headers.get("location")!, "http://x").pathname).toBe("/users");
  });

  it("会话其实又有效了：不清 cookie，回 /users", async () => {
    jest.mocked(getSession).mockResolvedValue({
      status: "authenticated",
      session: {} as never,
    });
    const res = await GET();
    expect(res.status).toBe(303);
    expect(res.headers.get("set-cookie")).toBeNull();
    expect(new URL(res.headers.get("location")!, "http://x").pathname).toBe("/users");
  });

  it("响应不可缓存", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "unverified" });
    const res = await GET();
    expect(res.headers.get("cache-control")).toBe("no-store");
  });
});
