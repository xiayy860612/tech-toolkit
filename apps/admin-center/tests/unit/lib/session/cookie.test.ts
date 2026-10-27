/**
 * @jest-environment node
 */
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import {
  clearSessionCookie,
  readSessionId,
  sessionCookieOptions,
  setSessionCookie,
} from "@/lib/session/cookie";

jest.mock("next/headers", () => ({ cookies: jest.fn() }));

const saved = { ...process.env };
const store = { get: jest.fn(), set: jest.fn() };

beforeEach(() => {
  process.env.UAM_BASE_URL = "http://uam.test";
  delete process.env.SESSION_COOKIE_NAME;
  jest.mocked(cookies).mockResolvedValue(store as never);
});
afterEach(() => {
  process.env = { ...saved };
  jest.resetAllMocks();
});

describe("会话 cookie（FR-003）", () => {
  it("属性：HttpOnly、Secure、SameSite=Strict、Path=/，不设 Domain，Max-Age 取传入值", () => {
    expect(sessionCookieOptions(1234)).toEqual({
      httpOnly: true,
      secure: true,
      sameSite: "strict",
      path: "/",
      maxAge: 1234,
    });
    expect(sessionCookieOptions(1234)).not.toHaveProperty("domain");
  });

  it("默认名称 __Host-asid，可用 SESSION_COOKIE_NAME 覆盖", async () => {
    await setSessionCookie("sid-1", 600);
    expect(store.set).toHaveBeenCalledWith("__Host-asid", "sid-1", sessionCookieOptions(600));

    process.env.SESSION_COOKIE_NAME = "asid";
    await setSessionCookie("sid-2", 300);
    expect(store.set).toHaveBeenLastCalledWith("asid", "sid-2", sessionCookieOptions(300));
  });

  it("readSessionId：有则返回，空串或没有返回 undefined", async () => {
    store.get.mockReturnValueOnce({ name: "__Host-asid", value: "abc" });
    expect(await readSessionId()).toBe("abc");
    expect(store.get).toHaveBeenCalledWith("__Host-asid");
    store.get.mockReturnValueOnce({ name: "__Host-asid", value: "" });
    expect(await readSessionId()).toBeUndefined();
    store.get.mockReturnValueOnce(undefined);
    expect(await readSessionId()).toBeUndefined();
  });

  it("clearSessionCookie（Server Action 上下文）：用同样的属性写一个立即过期的值", async () => {
    await clearSessionCookie();
    expect(store.set).toHaveBeenCalledWith("__Host-asid", "", sessionCookieOptions(0));
  });

  it("clearSessionCookie（路由响应）：写在响应上", async () => {
    const res = NextResponse.json({});
    await clearSessionCookie(res);
    expect(store.set).not.toHaveBeenCalled();
    const header = res.headers.get("set-cookie")!;
    expect(header).toMatch(/^__Host-asid=;/);
    expect(header).toMatch(/Max-Age=0/i);
    expect(header).toMatch(/Secure/i);
  });
});
