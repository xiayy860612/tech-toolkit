// @vitest-environment node
import { NextRequest } from "next/server";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "@/app/api/v1/[...path]/route";
import { apiRequest, setSessionExpiredHandler } from "@/lib/api/client";
import { loginUrlFor } from "@/lib/navigation";
import { clearResolveCache } from "@/server/session/resolver";
import { IDENTITY, json, problem, setupBffEnv } from "@tests/helpers/bff-env";

beforeAll(async () => {
  await setupBffEnv();
});

let backendUsers: () => Response;
let resolveCalls = 0;
let browserFetch: ReturnType<typeof vi.fn>;

beforeEach(() => {
  clearResolveCache();
  resolveCalls = 0;
  backendUsers = () => json(200, { items: [] });
  process.env.SESSION_RESOLVE_CACHE_TTL_SECONDS = "5";
  // 全局 fetch 同时扮演「浏览器 → BFF」与「BFF → 后端」两段，按 URL 区分
  browserFetch = vi.fn(async (input: string, init?: RequestInit) => {
    if (input.startsWith("/api/v1/")) {
      const req = new NextRequest(`http://app.test${input}`, {
        method: init?.method,
        headers: { cookie: "SESSION_ID=session-token-abcdef-123456" },
      });
      return GET(req, {
        params: Promise.resolve({ path: input.replace("/api/v1/", "").split("/") }),
      });
    }
    if (input.endsWith("/auth/sessions/resolve")) {
      resolveCalls++;
      return json(200, IDENTITY);
    }
    return backendUsers();
  });
  vi.stubGlobal("fetch", browserFetch);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("session expiry (quickstart scenario 14)", () => {
  it("re-confirms with the backend after the resolve cache TTL, never before", async () => {
    vi.useFakeTimers();
    await apiRequest("/users");
    await apiRequest("/users");
    expect(resolveCalls).toBe(1);
    vi.advanceTimersByTime(5_001);
    await apiRequest("/users");
    expect(resolveCalls).toBe(2);
  });

  it("TTL=0 confirms on every request", async () => {
    process.env.SESSION_RESOLVE_CACHE_TTL_SECONDS = "0";
    await apiRequest("/users");
    await apiRequest("/users");
    expect(resolveCalls).toBe(2);
  });

  it("a session revoked during the cache window is rejected by the backend on the next call: cookie cleared, page sent to /login?next=", async () => {
    const onExpired = vi.fn();
    setSessionExpiredHandler(onExpired);

    await apiRequest("/users"); // 缓存了 resolve 结果
    backendUsers = () => problem(401, "UNAUTHORIZED"); // 期间后端撤销了会话

    const raw = await fetch("/api/v1/users");
    expect(raw.status).toBe(401);
    expect(raw.headers.get("set-cookie")).toContain("Max-Age=0");

    await expect(apiRequest("/users")).rejects.toMatchObject({ status: 401 });
    expect(onExpired).toHaveBeenCalled();
    expect(loginUrlFor("/users/abc", "?tab=1")).toBe("/login?next=%2Fusers%2Fabc%3Ftab%3D1");
  });

  it("drops the cached identity after a 401 so the next request asks the backend again", async () => {
    setSessionExpiredHandler(() => {});
    await apiRequest("/users");
    expect(resolveCalls).toBe(1);
    backendUsers = () => problem(401, "UNAUTHORIZED");
    await apiRequest("/users").catch(() => undefined);
    backendUsers = () => json(200, { items: [] });
    await apiRequest("/users");
    expect(resolveCalls).toBe(2);
  });
});
