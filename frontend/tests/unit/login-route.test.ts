// @vitest-environment node
import RedisMock from "ioredis-mock";
import { NextRequest } from "next/server";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { json, problem, setupBffEnv } from "@tests/helpers/bff-env";

const redis = new RedisMock();
vi.mock("@/server/redis", () => ({ getRedis: () => redis }));

import { POST } from "@/app/api/v1/auth/login/route";

let env: Awaited<ReturnType<typeof setupBffEnv>>;
beforeAll(async () => {
  env = await setupBffEnv();
});

const TOKEN = "opaque-session-token-ABCDEFGHIJKLMNOP";
const LOGIN_RESULT = {
  me: {
    userId: 42,
    tenantId: 0,
    displayName: "管理员",
    role: "TENANT_ADMIN",
    mustChangePassword: true,
  },
  sessionToken: TOKEN,
  expiresAt: new Date(Date.now() + 8 * 3600_000).toISOString(),
};

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(async () => {
  await redis.flushall();
  fetchMock = vi.fn(async () => json(200, LOGIN_RESULT));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const SAME_ORIGIN = {
  origin: "http://app.test",
  "sec-fetch-site": "same-origin",
  "content-type": "application/json",
  "user-agent": "Mozilla/5.0 TestBrowser",
};

const login = (body: unknown = { username: "admin", password: "pw" }, headers = SAME_ORIGIN) =>
  POST(
    new NextRequest("http://app.test/api/v1/auth/login", {
      method: "POST",
      headers,
      body: typeof body === "string" ? body : JSON.stringify(body),
    }),
  );

describe("POST /api/v1/auth/login", () => {
  it("sets the session cookie from the backend token and returns only browser-safe fields", async () => {
    const res = await login();
    expect(res.status).toBe(200);
    const cookie = res.headers.get("set-cookie")!;
    expect(cookie).toContain(`SESSION_ID=${TOKEN}`);
    expect(cookie).toContain("HttpOnly");
    const text = await res.text();
    expect(JSON.parse(text)).toEqual({
      userId: LOGIN_RESULT.me.userId,
      displayName: "管理员",
      mustChangePassword: true,
    });
    for (const secret of [TOKEN, "sessionToken", "tenantId", "role"]) {
      expect(text).not.toContain(secret);
    }
  });

  it("calls the backend with a service JWT (default tenant) and the browser User-Agent", async () => {
    process.env.DEFAULT_TENANT_ID = "0";
    await login();
    const [url, init] = fetchMock.mock.calls[0]! as [string, RequestInit];
    expect(url).toBe("http://backend.test/auth/login");
    const body = JSON.parse(init.body as string);
    expect(body).toEqual({
      username: "admin",
      password: "pw",
      userAgent: "Mozilla/5.0 TestBrowser",
    });
    expect(body).not.toHaveProperty("tenantId");
    const { payload } = await env.verify(new Headers(init.headers).get("authorization")!.slice(7));
    expect(payload).toMatchObject({ typ: "service", tenant_id: 0 });
  });

  it("returns the unified 401 and sets no cookie when the backend rejects", async () => {
    fetchMock.mockResolvedValueOnce(problem(401, "INVALID_CREDENTIALS"));
    const res = await login();
    expect(res.status).toBe(401);
    expect((await res.json()).code).toBe("INVALID_CREDENTIALS");
    expect(res.headers.get("set-cookie")).toBeNull();
  });

  it("rate-limits per IP before touching the backend: 429 + Retry-After", async () => {
    for (let i = 0; i < 3; i++) expect((await login()).status).toBe(200);
    fetchMock.mockClear();
    const res = await login();
    expect(res.status).toBe(429);
    expect(Number(res.headers.get("retry-after"))).toBeGreaterThanOrEqual(1);
    expect((await res.json()).code).toBe("TOO_MANY_REQUESTS");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects cross-site posts with 403", async () => {
    const res = await login(undefined, { ...SAME_ORIGIN, "sec-fetch-site": "cross-site" });
    expect(res.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    ["not json"],
    [{}],
    [{ username: "a" }],
    [{ username: "", password: "x" }],
    [{ username: 1, password: 2 }],
  ])("answers 400 for malformed credentials %j without calling the backend", async (body) => {
    const res = await login(body as never);
    expect(res.status).toBe(400);
    expect((await res.json()).code).toBe("VALIDATION_FAILED");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("answers 502 when the backend is down", async () => {
    fetchMock.mockRejectedValueOnce(new Error("ECONNREFUSED"));
    const res = await login();
    expect(res.status).toBe(502);
    expect(res.headers.get("set-cookie")).toBeNull();
  });

  it("fails open (logs, still serves) when Redis is unavailable", async () => {
    const spy = vi.spyOn(redis, "incr").mockRejectedValueOnce(new Error("redis down"));
    const res = await login();
    expect(res.status).toBe(200);
    spy.mockRestore();
  });

  it("never logs the password or the session token", async () => {
    const spies = (["log", "info", "warn", "error", "debug"] as const).map((m) =>
      vi.spyOn(console, m),
    );
    fetchMock.mockRejectedValueOnce(new Error("ECONNREFUSED"));
    await login({ username: "admin", password: "Super-Secret-Pw-1!" });
    await login({ username: "admin", password: "Super-Secret-Pw-1!" });
    for (const s of spies) {
      const out = JSON.stringify(s.mock.calls);
      expect(out).not.toContain("Super-Secret-Pw-1!");
      expect(out).not.toContain(TOKEN);
      s.mockRestore();
    }
  });
});
