// @vitest-environment node
import { NextRequest } from "next/server";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { POST } from "@/app/api/v1/auth/logout/route";
import { clearResolveCache } from "@/server/session/resolver";
import { IDENTITY, json, problem, setupBffEnv } from "@tests/helpers/bff-env";

let env: Awaited<ReturnType<typeof setupBffEnv>>;
beforeAll(async () => {
  env = await setupBffEnv();
});

let fetchMock: ReturnType<typeof vi.fn>;
let logoutResponse: () => Response;
beforeEach(() => {
  clearResolveCache();
  process.env.SESSION_RESOLVE_CACHE_TTL_SECONDS = "0";
  logoutResponse = () => new Response(null, { status: 204 });
  fetchMock = vi.fn(async (url: string) =>
    url.endsWith("/auth/sessions/resolve") ? json(200, IDENTITY) : logoutResponse(),
  );
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const SAME_ORIGIN = { origin: "http://app.test", "sec-fetch-site": "same-origin" };
const logout = (
  cookie: string | null = "SESSION_ID=session-token-abcdef-123456",
  headers = SAME_ORIGIN,
) =>
  POST(
    new NextRequest("http://app.test/api/v1/auth/logout", {
      method: "POST",
      headers: { ...headers, ...(cookie ? { cookie } : {}) },
    }),
  );

const logoutCalls = () => fetchMock.mock.calls.filter(([u]) => String(u).endsWith("/auth/logout"));

describe("POST /api/v1/auth/logout", () => {
  it("logs out at the backend with the user JWT, then clears the cookie", async () => {
    const res = await logout();
    expect(res.status).toBe(204);
    expect(res.headers.get("set-cookie")).toContain("Max-Age=0");
    const [, init] = logoutCalls()[0]! as [string, RequestInit];
    const { payload } = await env.verify(new Headers(init.headers).get("authorization")!.slice(7));
    expect(payload).toMatchObject({ typ: "user", sid: IDENTITY.sessionId });
  });

  it("still clears the cookie when the backend says the session is already invalid", async () => {
    logoutResponse = () => problem(401, "UNAUTHORIZED");
    const res = await logout();
    expect(res.status).toBe(204);
    expect(res.headers.get("set-cookie")).toContain("Max-Age=0");
  });

  it("is idempotent without a session cookie", async () => {
    const res = await logout(null);
    expect(res.status).toBe(204);
    expect(res.headers.get("set-cookie")).toContain("Max-Age=0");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("clears the cookie when the session no longer resolves", async () => {
    fetchMock.mockImplementation(async (url: string) =>
      url.endsWith("/auth/sessions/resolve") ? problem(401, "UNAUTHORIZED") : logoutResponse(),
    );
    const res = await logout();
    expect(res.status).toBe(204);
    expect(logoutCalls()).toHaveLength(0);
  });

  it("clears the cookie but reports 502 when the backend is unreachable", async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (url.endsWith("/auth/sessions/resolve")) return json(200, IDENTITY);
      throw new Error("ECONNREFUSED");
    });
    const res = await logout();
    expect(res.status).toBe(502);
    expect(res.headers.get("set-cookie")).toContain("Max-Age=0");
  });

  it("rejects cross-site logout with 403", async () => {
    const res = await logout(undefined, { ...SAME_ORIGIN, "sec-fetch-site": "cross-site" });
    expect(res.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
