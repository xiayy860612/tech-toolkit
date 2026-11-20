// @vitest-environment node
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { backendFetch } from "@/server/backend/client";
import { IDENTITY, json, problem, setupBffEnv } from "@tests/helpers/bff-env";

let env: Awaited<ReturnType<typeof setupBffEnv>>;
beforeAll(async () => {
  env = await setupBffEnv();
});
afterEach(() => vi.unstubAllGlobals());

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetchMock = vi.fn(async () => json(200, { ok: true }));
  vi.stubGlobal("fetch", fetchMock);
});

function sentHeaders(): Headers {
  return new Headers((fetchMock.mock.calls[0]![1] as RequestInit).headers);
}

describe("backend client", () => {
  it("signs a typ=user JWT and sends it as Bearer to BACKEND_URL + path", async () => {
    await backendFetch("/users?page=0", {
      auth: { kind: "user", identity: IDENTITY },
      cip: "1.1.1.1",
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("http://backend.test/users?page=0");
    const auth = sentHeaders().get("authorization")!;
    expect(auth.startsWith("Bearer ")).toBe(true);
    const { payload } = await env.verify(auth.slice(7));
    expect(payload.typ).toBe("user");
    expect(payload.sid).toBe(IDENTITY.sessionId);
  });

  it("signs a typ=service JWT for service calls", async () => {
    await backendFetch("/auth/login", {
      method: "POST",
      body: "{}",
      auth: { kind: "service" },
      cip: "1.1.1.1",
    });
    const { payload } = await env.verify(sentHeaders().get("authorization")!.slice(7));
    expect(payload.typ).toBe("service");
  });

  it("never forwards browser Cookie / Authorization / tenant headers", async () => {
    await backendFetch("/users", {
      auth: { kind: "user", identity: IDENTITY },
      cip: "1.1.1.1",
      headers: {
        cookie: "SESSION_ID=abc",
        authorization: "Bearer attacker",
        "x-tenant-id": "1",
        "X-Tenant-Code": "acme",
        accept: "application/json",
        "content-type": "application/json",
      },
    });
    const h = sentHeaders();
    expect(h.get("cookie")).toBeNull();
    expect(h.get("x-tenant-id")).toBeNull();
    expect(h.get("x-tenant-code")).toBeNull();
    expect(h.get("authorization")).not.toContain("attacker");
    expect(h.get("accept")).toBe("application/json");
    expect(h.get("content-type")).toBe("application/json");
  });

  it("passes backend problem+json through untouched", async () => {
    fetchMock.mockResolvedValueOnce(problem(409, "USERNAME_TAKEN"));
    const res = await backendFetch("/users", {
      method: "POST",
      body: "{}",
      auth: { kind: "user", identity: IDENTITY },
      cip: "1.1.1.1",
    });
    expect(res.status).toBe(409);
    expect((await res.json()).code).toBe("USERNAME_TAKEN");
  });

  it("times out", async () => {
    process.env.BACKEND_TIMEOUT_MS = "20";
    fetchMock.mockImplementationOnce(
      (_u: string, init: RequestInit) =>
        new Promise((_res, rej) => {
          init.signal!.addEventListener("abort", () => rej(init.signal!.reason));
        }),
    );
    await expect(
      backendFetch("/users", { auth: { kind: "service" }, cip: "1.1.1.1" }),
    ).rejects.toThrow();
    process.env.BACKEND_TIMEOUT_MS = "2000";
  });

  it("does not write the JWT to logs", async () => {
    const spies = (["log", "info", "warn", "error", "debug"] as const).map((m) =>
      vi.spyOn(console, m),
    );
    await backendFetch("/users", { auth: { kind: "user", identity: IDENTITY }, cip: "1.1.1.1" });
    const token = sentHeaders().get("authorization")!.slice(7);
    for (const s of spies) {
      expect(JSON.stringify(s.mock.calls)).not.toContain(token);
      s.mockRestore();
    }
  });
});
