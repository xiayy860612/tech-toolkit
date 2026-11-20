// @vitest-environment node
import RedisMock from "ioredis-mock";
import { NextRequest } from "next/server";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { json, problem, IDENTITY, setupBffEnv } from "@tests/helpers/bff-env";

const redis = new RedisMock();
vi.mock("@/server/redis", () => ({ getRedis: () => redis }));

import { GET, POST } from "@/app/api/v1/[...path]/route";
import { POST as login } from "@/app/api/v1/auth/login/route";
import { POST as logout } from "@/app/api/v1/auth/logout/route";
import { clearResolveCache } from "@/server/session/resolver";

const PASSWORD = "Super-Secret-Pw-1!";
const NEW_PASSWORD = "Brand-New-Secret-Pw-2@";
const TEMP_PASSWORD = "Temp-Issued-Secret-3#";
const SESSION_TOKEN = "opaque-session-token-ZZZZZZZZZZZZZZZZ";
const SECRETS = [PASSWORD, NEW_PASSWORD, TEMP_PASSWORD, SESSION_TOKEN];

beforeAll(async () => {
  await setupBffEnv();
});

const jwtsSeen = new Set<string>();
let fail = false;
let spies: ReturnType<typeof vi.spyOn>[];

beforeEach(async () => {
  await redis.flushall();
  clearResolveCache();
  jwtsSeen.clear();
  fail = false;
  spies = (["log", "info", "warn", "error", "debug"] as const).map((m) => vi.spyOn(console, m));
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      const auth = new Headers(init?.headers).get("authorization");
      if (auth) jwtsSeen.add(auth.slice(7));
      if (fail) throw new Error(`boom ${init?.body ?? ""}`);
      if (url.endsWith("/auth/login")) {
        return json(200, {
          me: {
            userId: IDENTITY.userId,
            tenantId: 0,
            displayName: "A",
            role: "TENANT_ADMIN",
            mustChangePassword: false,
          },
          sessionToken: SESSION_TOKEN,
          expiresAt: new Date(Date.now() + 3600_000).toISOString(),
        });
      }
      if (url.endsWith("/auth/sessions/resolve")) return json(200, IDENTITY);
      if (url.endsWith("/users"))
        return json(201, { account: { temporaryPassword: TEMP_PASSWORD } });
      return new Response(null, { status: 204 });
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

const SAME = {
  origin: "http://app.test",
  "sec-fetch-site": "same-origin",
  "content-type": "application/json",
  cookie: `SESSION_ID=${SESSION_TOKEN}`,
};
const post = (path: string, body: unknown) =>
  new NextRequest(`http://app.test/api/v1/${path}`, {
    method: "POST",
    headers: SAME,
    body: JSON.stringify(body),
  });
const ctx = (path: string) => ({ params: Promise.resolve({ path: path.split("/") }) });

/** 所有 console 输出的文本形式；Error 要展开 message 与 stack（JSON.stringify 会把它序列化成 {}）。 */
function logged(): string {
  return JSON.stringify(
    spies.flatMap((s) => s.mock.calls),
    (_key, value) =>
      value instanceof Error ? `${value.name}: ${value.message}\n${value.stack ?? ""}` : value,
  );
}

describe("log redaction (FR-006)", () => {
  it("login, change-password, create-user, reset-password and logout never log secrets or JWTs", async () => {
    await login(post("auth/login", { username: "admin", password: PASSWORD }));
    await POST(
      post("auth/change-password", { currentPassword: PASSWORD, newPassword: NEW_PASSWORD }),
      ctx("auth/change-password"),
    );
    await POST(
      post("users", {
        displayName: "A",
        role: "MEMBER",
        account: { type: "USERNAME_PASSWORD", username: "abc", temporaryPassword: TEMP_PASSWORD },
      }),
      ctx("users"),
    );
    await POST(
      post("users/1/reset-password", { temporaryPassword: TEMP_PASSWORD }),
      ctx("users/1/reset-password"),
    );
    await GET(new NextRequest("http://app.test/api/v1/users", { headers: SAME }), ctx("users"));
    await logout(post("auth/logout", {}));

    expect(jwtsSeen.size).toBeGreaterThan(0);
    const out = logged();
    for (const secret of [...SECRETS, ...jwtsSeen]) expect(out).not.toContain(secret);
  });

  it("failure paths log only error categories, not request bodies, passwords or tokens", async () => {
    fail = true;
    await login(post("auth/login", { username: "admin", password: PASSWORD }));
    await POST(
      post("auth/change-password", { currentPassword: PASSWORD, newPassword: NEW_PASSWORD }),
      ctx("auth/change-password"),
    );
    await logout(post("auth/logout", {}));

    const out = logged();
    for (const secret of [...SECRETS, ...jwtsSeen]) expect(out).not.toContain(secret);
    expect(out).not.toContain("boom"); // 连错误消息本身（可能回显请求体）也不记录
  });

  it("backend error bodies are not logged either", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) =>
        url.endsWith("/auth/sessions/resolve")
          ? json(200, IDENTITY)
          : problem(500, `echo ${PASSWORD}`),
      ),
    );
    await POST(
      post("auth/change-password", { currentPassword: PASSWORD, newPassword: NEW_PASSWORD }),
      ctx("auth/change-password"),
    );
    expect(logged()).not.toContain(PASSWORD);
  });
});
