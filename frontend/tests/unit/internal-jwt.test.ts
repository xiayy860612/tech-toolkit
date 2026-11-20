// @vitest-environment node
import { decodeProtectedHeader } from "jose";
import { beforeAll, describe, expect, it } from "vitest";
import { signServiceJwt, signUserJwt } from "@/server/backend/jwt";
import { IDENTITY, setupBffEnv } from "@tests/helpers/bff-env";

let env: Awaited<ReturnType<typeof setupBffEnv>>;
beforeAll(async () => {
  env = await setupBffEnv();
});

describe("internal JWT", () => {
  it("typ=user carries sub (integer user id), tenant_id (number), sid, cip and standard claims", async () => {
    const token = await signUserJwt(IDENTITY, "203.0.113.9");
    const { payload } = await env.verify(token);
    expect(payload.typ).toBe("user");
    expect(payload.sub).toBe(String(IDENTITY.userId));
    expect(payload.tenant_id).toBe(0);
    expect(payload).not.toHaveProperty("account_id");
    expect(payload.sid).toBe(IDENTITY.sessionId);
    expect(payload.cip).toBe("203.0.113.9");
    expect(payload.iss).toBe("admin-center-bff");
    expect(payload.aud).toBe("tenant-core-api");
    expect(payload.jti).toBeTruthy();
    expect(payload.exp! - payload.iat!).toBe(60);
  });

  it("never carries a role claim", async () => {
    const { payload } = await env.verify(await signUserJwt(IDENTITY, "1.1.1.1"));
    expect(payload).not.toHaveProperty("role");
  });

  it("typ=service has tenant_id from DEFAULT_TENANT_ID and no user claims", async () => {
    process.env.DEFAULT_TENANT_ID = "7";
    const { payload } = await env.verify(await signServiceJwt("1.1.1.1"));
    process.env.DEFAULT_TENANT_ID = "0";
    expect(payload.typ).toBe("service");
    expect(payload.tenant_id).toBe(7);
    for (const k of ["sub", "sid"]) expect(payload).not.toHaveProperty(k);
  });

  it("is signed with EdDSA and carries the kid", async () => {
    const header = decodeProtectedHeader(await signServiceJwt("1.1.1.1"));
    expect(header.alg).toBe("EdDSA");
    expect(header.kid).toBe("test-kid");
  });

  it("uses a different jti every time", async () => {
    const a = (await env.verify(await signServiceJwt("1.1.1.1"))).payload.jti;
    const b = (await env.verify(await signServiceJwt("1.1.1.1"))).payload.jti;
    expect(a).not.toBe(b);
  });

  it("uses the identity's tenant (from resolve), not the environment default", async () => {
    const { payload } = await env.verify(
      await signUserJwt({ ...IDENTITY, tenantId: 5 }, "1.1.1.1"),
    );
    expect(payload.tenant_id).toBe(5);
  });
});
