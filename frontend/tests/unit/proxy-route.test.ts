// @vitest-environment node
import { NextRequest } from "next/server";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { DELETE, GET, PATCH, POST } from "@/app/api/v1/[...path]/route";
import { clearResolveCache } from "@/server/session/resolver";
import { IDENTITY, json, problem, setupBffEnv } from "@tests/helpers/bff-env";

let env: Awaited<ReturnType<typeof setupBffEnv>>;
beforeAll(async () => {
  env = await setupBffEnv();
});

type Handler = typeof GET;
let fetchMock: ReturnType<typeof vi.fn>;
let resolveResponse: () => Response;
let backendResponse: () => Response;

beforeEach(() => {
  clearResolveCache();
  process.env.SESSION_RESOLVE_CACHE_TTL_SECONDS = "0";
  resolveResponse = () => json(200, IDENTITY);
  backendResponse = () => json(200, { items: [] });
  fetchMock = vi.fn(async (url: string) =>
    url.endsWith("/auth/sessions/resolve") ? resolveResponse() : backendResponse(),
  );
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const SAME_ORIGIN = { origin: "http://app.test", "sec-fetch-site": "same-origin" };

function call(
  handler: Handler,
  path: string,
  init: { method?: string; headers?: Record<string, string>; body?: string; cookie?: string | null } = {},
) {
  const headers: Record<string, string> = { ...init.headers };
  const cookie = init.cookie === undefined ? "SESSION_ID=session-token-abcdef-123456" : init.cookie;
  if (cookie) headers.cookie = cookie;
  const [pathname, qs] = path.split("?");
  const req = new NextRequest(`http://app.test/api/v1/${path}`, {
    method: init.method ?? "GET",
    headers,
    body: init.body,
  });
  return handler(req, { params: Promise.resolve({ path: pathname!.split("/") }) });
}

const backendCalls = () =>
  fetchMock.mock.calls.filter(([u]) => !String(u).endsWith("/auth/sessions/resolve"));

describe("proxy route", () => {
  it("returns 401 problem+json without a session cookie and never calls the backend", async () => {
    const res = await call(GET, "users", { cookie: null });
    expect(res.status).toBe(401);
    expect(res.headers.get("content-type")).toContain("application/problem+json");
    expect((await res.json()).code).toBe("UNAUTHORIZED");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("resolves the session, signs a user JWT from it, forwards and returns status + body", async () => {
    backendResponse = () => json(200, { items: [{ id: "u1" }] });
    const res = await call(GET, "users?page=0&size=20");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ items: [{ id: "u1" }] });
    const [url, init] = backendCalls()[0]! as [string, RequestInit];
    expect(url).toBe("http://backend.test/users?page=0&size=20");
    const bearer = new Headers(init.headers).get("authorization")!.slice(7);
    const { payload } = await env.verify(bearer);
    expect(payload).toMatchObject({
      typ: "user",
      sub: IDENTITY.userId,
      sid: IDENTITY.sessionId,
      account_id: IDENTITY.accountId,
      tenant_id: 0,
    });
  });

  it("passes backend problem responses through (status and code)", async () => {
    backendResponse = () => problem(409, "USERNAME_TAKEN");
    const res = await call(POST, "users", {
      method: "POST",
      headers: SAME_ORIGIN,
      body: "{}",
    });
    expect(res.status).toBe(409);
    expect((await res.json()).code).toBe("USERNAME_TAKEN");
    expect(res.headers.get("set-cookie")).toBeNull();
  });

  it("supports bodiless responses (204)", async () => {
    backendResponse = () => new Response(null, { status: 204 });
    const res = await call(DELETE, "sessions/abc", { method: "DELETE", headers: SAME_ORIGIN });
    expect(res.status).toBe(204);
  });

  it.each([
    ["mismatched Origin", { origin: "http://evil.test", "sec-fetch-site": "same-origin" }],
    ["cross-site Sec-Fetch-Site", { origin: "http://app.test", "sec-fetch-site": "cross-site" }],
    ["same-site (not same-origin)", { origin: "http://app.test", "sec-fetch-site": "same-site" }],
    ["missing Origin", { "sec-fetch-site": "same-origin" }],
    ["missing Sec-Fetch-Site", { origin: "http://app.test" }],
  ])("rejects non-GET with %s as 403 before touching the backend", async (_n, headers) => {
    const res = await call(PATCH, "users/u1", { method: "PATCH", headers, body: "{}" });
    expect(res.status).toBe(403);
    expect((await res.json()).code).toBe("CROSS_SITE_REQUEST");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("does not require Origin headers for GET", async () => {
    expect((await call(GET, "users")).status).toBe(200);
  });

  it("clears the cookie when resolve says the session is invalid", async () => {
    resolveResponse = () => problem(401, "UNAUTHORIZED");
    const res = await call(GET, "users");
    expect(res.status).toBe(401);
    expect(res.headers.get("set-cookie")).toContain("Max-Age=0");
    expect(backendCalls()).toHaveLength(0);
  });

  it("clears the cookie when the backend answers 401", async () => {
    backendResponse = () => problem(401, "UNAUTHORIZED");
    const res = await call(GET, "users");
    expect(res.status).toBe(401);
    expect(res.headers.get("set-cookie")).toContain("Max-Age=0");
  });

  it("does not clear the cookie for 403 FORBIDDEN", async () => {
    backendResponse = () => problem(403, "FORBIDDEN");
    const res = await call(GET, "users");
    expect(res.status).toBe(403);
    expect(res.headers.get("set-cookie")).toBeNull();
  });

  it("does not forward the browser's Cookie, Authorization or tenant headers", async () => {
    await call(GET, "users", {
      headers: {
        authorization: "Bearer attacker",
        "x-tenant-id": "1",
        "x-tenant-code": "acme",
        accept: "application/json",
      },
    });
    const [, init] = backendCalls()[0]! as [string, RequestInit];
    const h = new Headers(init.headers);
    expect(h.get("cookie")).toBeNull();
    expect(h.get("x-tenant-id")).toBeNull();
    expect(h.get("x-tenant-code")).toBeNull();
    expect(h.get("authorization")).not.toContain("attacker");
    expect(h.get("accept")).toBe("application/json");
  });

  it("never exposes the service-only resolve endpoint to browsers", async () => {
    const res = await call(POST, "auth/sessions/resolve", {
      method: "POST",
      headers: SAME_ORIGIN,
      body: '{"sessionToken":"x"}',
    });
    expect(res.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("answers 502 when the backend is unreachable", async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (url.endsWith("/auth/sessions/resolve")) return json(200, IDENTITY);
      throw new Error("ECONNREFUSED");
    });
    const res = await call(GET, "users");
    expect(res.status).toBe(502);
    expect((await res.json()).code).toBe("BACKEND_UNAVAILABLE");
  });

  it("answers 502 when session resolution itself fails", async () => {
    resolveResponse = () => problem(500, "INTERNAL");
    const res = await call(GET, "users");
    expect(res.status).toBe(502);
  });

  it("keeps no session state: the second request resolves again when the cache is off", async () => {
    await call(GET, "users");
    await call(GET, "users");
    const resolves = fetchMock.mock.calls.filter(([u]) => String(u).endsWith("/auth/sessions/resolve"));
    expect(resolves).toHaveLength(2);
  });
});
