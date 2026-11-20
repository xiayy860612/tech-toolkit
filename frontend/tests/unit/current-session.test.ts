// @vitest-environment node
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { clearResolveCache } from "@/server/session/resolver";
import { getCurrentSession } from "@/server/session/current";
import { IDENTITY, json, problem, setupBffEnv } from "@tests/helpers/bff-env";

let env: Awaited<ReturnType<typeof setupBffEnv>>;
beforeAll(async () => {
  env = await setupBffEnv();
});

const ME = {
  userId: IDENTITY.userId,
  accountId: IDENTITY.accountId,
  tenantId: 0,
  displayName: "管理员",
  role: "MEMBER",
  mustChangePassword: false,
};

let fetchMock: ReturnType<typeof vi.fn>;
let meResponse: () => Response;
let resolveResponse: () => Response;
beforeEach(() => {
  clearResolveCache();
  process.env.SESSION_RESOLVE_CACHE_TTL_SECONDS = "0";
  meResponse = () => json(200, ME);
  resolveResponse = () => json(200, IDENTITY);
  fetchMock = vi.fn(async (url: string) =>
    url.endsWith("/auth/sessions/resolve") ? resolveResponse() : meResponse(),
  );
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const withCookie = new Headers({ cookie: "SESSION_ID=session-token-abcdef-123456" });

describe("getCurrentSession", () => {
  it("returns null without a session cookie", async () => {
    expect(await getCurrentSession(new Headers())).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("returns null when the session does not resolve", async () => {
    resolveResponse = () => problem(401, "UNAUTHORIZED");
    expect(await getCurrentSession(withCookie)).toBeNull();
  });

  it("returns null when /auth/me says 401", async () => {
    meResponse = () => problem(401, "UNAUTHORIZED");
    expect(await getCurrentSession(withCookie)).toBeNull();
  });

  it("takes the role from /auth/me (database truth), using a user JWT", async () => {
    const me = await getCurrentSession(withCookie);
    expect(me?.role).toBe("MEMBER");
    const call = fetchMock.mock.calls.find(([u]) => String(u).endsWith("/auth/me"))! as [
      string,
      RequestInit,
    ];
    const { payload } = await env.verify(
      new Headers(call[1].headers).get("authorization")!.slice(7),
    );
    expect(payload).toMatchObject({ typ: "user", sid: IDENTITY.sessionId });
  });

  it("throws on unexpected backend failures", async () => {
    meResponse = () => problem(500, "INTERNAL");
    await expect(getCurrentSession(withCookie)).rejects.toThrow();
  });
});
