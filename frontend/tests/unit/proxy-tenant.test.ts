// @vitest-environment node
import { NextRequest } from "next/server";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { GET, POST } from "@/app/api/v1/[...path]/route";
import { clearResolveCache } from "@/server/session/resolver";
import { IDENTITY, json, setupBffEnv } from "@tests/helpers/bff-env";

let env: Awaited<ReturnType<typeof setupBffEnv>>;
beforeAll(async () => {
  env = await setupBffEnv();
});

let fetchMock: ReturnType<typeof vi.fn>;
let identity = IDENTITY;
beforeEach(() => {
  clearResolveCache();
  process.env.SESSION_RESOLVE_CACHE_TTL_SECONDS = "0";
  identity = IDENTITY;
  fetchMock = vi.fn(async (url: string) =>
    url.endsWith("/auth/sessions/resolve") ? json(200, identity) : json(200, {}),
  );
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const backendCall = () =>
  fetchMock.mock.calls.find(([u]) => !String(u).endsWith("/auth/sessions/resolve"))! as [
    string,
    RequestInit,
  ];

function req(
  path: string,
  init: { method?: string; headers?: Record<string, string>; body?: string } = {},
) {
  const r = new NextRequest(`http://app.test/api/v1/${path}`, {
    ...init,
    headers: { cookie: "SESSION_ID=session-token-abcdef-123456", ...init.headers },
  });
  return { r, ctx: { params: Promise.resolve({ path: path.split("?")[0]!.split("/") }) } };
}

describe("tenant is taken only from the resolved session", () => {
  it("signs the JWT with the tenant from resolve, ignoring X-Tenant-* / tenantId from the browser", async () => {
    identity = { ...IDENTITY, tenantId: 5 };
    const { r, ctx } = req("users?tenantId=9&page=0", {
      headers: { "x-tenant-id": "1", "x-tenant-code": "acme" },
    });
    await GET(r, ctx);
    const [url, init] = backendCall();
    const { payload } = await env.verify(new Headers(init.headers).get("authorization")!.slice(7));
    expect(payload.tenant_id).toBe(5);
    expect(url).toBe("http://backend.test/users?page=0");
    const h = new Headers(init.headers);
    expect(h.get("x-tenant-id")).toBeNull();
    expect(h.get("x-tenant-code")).toBeNull();
  });

  it("drops tenant_id / tenantId query params but keeps the rest", async () => {
    const { r, ctx } = req("users?tenant_id=2&tenantId=3&keyword=ab&status=ACTIVE");
    await GET(r, ctx);
    expect(backendCall()[0]).toBe("http://backend.test/users?keyword=ab&status=ACTIVE");
  });

  it("strips tenantId from JSON bodies (also nested) before forwarding", async () => {
    const { r, ctx } = req("users", {
      method: "POST",
      headers: {
        origin: "http://app.test",
        "sec-fetch-site": "same-origin",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        displayName: "A",
        tenantId: 9,
        account: { username: "abc", tenant_id: 4 },
      }),
    });
    await POST(r, ctx);
    const forwarded = JSON.parse(String(backendCall()[1].body));
    expect(forwarded).toEqual({ displayName: "A", account: { username: "abc" } });
  });

  it("forwards non-JSON bodies untouched", async () => {
    const { r, ctx } = req("users", {
      method: "POST",
      headers: {
        origin: "http://app.test",
        "sec-fetch-site": "same-origin",
        "content-type": "text/plain",
      },
      body: "tenantId=9",
    });
    await POST(r, ctx);
    expect(String(backendCall()[1].body)).toBe("tenantId=9");
  });
});
