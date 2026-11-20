// @vitest-environment node
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { clearResolveCache, invalidateResolved, resolveSession } from "@/server/session/resolver";
import { IDENTITY, json, problem, setupBffEnv } from "@tests/helpers/bff-env";

let env: Awaited<ReturnType<typeof setupBffEnv>>;
beforeAll(async () => {
  env = await setupBffEnv();
});

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  process.env.SESSION_RESOLVE_CACHE_TTL_SECONDS = "5";
  clearResolveCache();
  fetchMock = vi.fn(async () => json(200, IDENTITY));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const TOKEN = "super-secret-session-token-0123456789";

describe("session resolver", () => {
  it("calls POST /auth/sessions/resolve with a typ=service JWT and the token in the body", async () => {
    const r = await resolveSession(TOKEN, "1.1.1.1");
    expect(r?.sessionId).toBe(IDENTITY.sessionId);
    const [url, init] = fetchMock.mock.calls[0]! as [string, RequestInit];
    expect(url).toBe("http://backend.test/auth/sessions/resolve");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ sessionToken: TOKEN });
    const auth = new Headers(init.headers).get("authorization")!;
    expect((await env.verify(auth.slice(7))).payload.typ).toBe("service");
  });

  it("caches successful results for the TTL, keyed by token hash", async () => {
    await resolveSession(TOKEN, "1.1.1.1");
    await resolveSession(TOKEN, "1.1.1.1");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await resolveSession(TOKEN + "x", "1.1.1.1");
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("re-confirms with the backend after the TTL expires", async () => {
    vi.useFakeTimers();
    await resolveSession(TOKEN, "1.1.1.1");
    vi.advanceTimersByTime(5_001);
    await resolveSession(TOKEN, "1.1.1.1");
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("TTL=0 disables the cache", async () => {
    process.env.SESSION_RESOLVE_CACHE_TTL_SECONDS = "0";
    await resolveSession(TOKEN, "1.1.1.1");
    await resolveSession(TOKEN, "1.1.1.1");
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("treats backend 401 as no session and clears the cache", async () => {
    await resolveSession(TOKEN, "1.1.1.1");
    invalidateResolved(TOKEN);
    fetchMock.mockResolvedValueOnce(problem(401, "UNAUTHORIZED"));
    expect(await resolveSession(TOKEN, "1.1.1.1")).toBeNull();
    // 401 不缓存：下一次仍会询问后端
    await resolveSession(TOKEN, "1.1.1.1");
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("throws on unexpected backend failures (does not pretend the session is invalid)", async () => {
    fetchMock.mockResolvedValueOnce(problem(500, "INTERNAL"));
    await expect(resolveSession(TOKEN, "1.1.1.1")).rejects.toThrow();
  });

  it("never logs the token", async () => {
    const spies = (["log", "info", "warn", "error", "debug"] as const).map((m) =>
      vi.spyOn(console, m),
    );
    fetchMock.mockResolvedValueOnce(problem(500, "INTERNAL"));
    await resolveSession(TOKEN, "1.1.1.1").catch(() => undefined);
    for (const s of spies) {
      expect(JSON.stringify(s.mock.calls)).not.toContain(TOKEN);
      s.mockRestore();
    }
  });
});
