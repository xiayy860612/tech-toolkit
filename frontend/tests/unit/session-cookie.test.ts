// @vitest-environment node
import { beforeEach, describe, expect, it } from "vitest";
import { buildClearCookie, buildSessionCookie, readSessionToken } from "@/server/session/cookie";
import { TEST_ENV_DEFAULTS } from "@tests/helpers/bff-env";

beforeEach(() => {
  Object.assign(process.env, TEST_ENV_DEFAULTS);
});

describe("session cookie", () => {
  const now = Date.parse("2030-01-01T00:00:00Z");

  it("sets HttpOnly; Secure; SameSite=Lax; Path=/ with the opaque token", () => {
    const c = buildSessionCookie("opaque-token-123", "2030-01-01T08:00:00Z", now);
    expect(c.startsWith("SESSION_ID=opaque-token-123;")).toBe(true);
    for (const attr of ["HttpOnly", "Secure", "SameSite=Lax", "Path=/"]) {
      expect(c).toContain(attr);
    }
  });

  it("derives Max-Age from the backend expiresAt", () => {
    const c = buildSessionCookie("t", "2030-01-01T08:00:00Z", now);
    expect(c).toContain(`Max-Age=${8 * 3600}`);
  });

  it("never yields a negative Max-Age", () => {
    expect(buildSessionCookie("t", "2029-01-01T00:00:00Z", now)).toContain("Max-Age=0");
  });

  it("omits Secure only when COOKIE_SECURE=false (local http dev)", () => {
    process.env.COOKIE_SECURE = "false";
    expect(buildSessionCookie("t", "2030-01-01T08:00:00Z", now)).not.toContain("Secure");
  });

  it("clears the cookie with Max-Age=0", () => {
    const c = buildClearCookie();
    expect(c.startsWith("SESSION_ID=;")).toBe(true);
    expect(c).toContain("Max-Age=0");
    expect(c).toContain("HttpOnly");
  });

  it("reads the token from the request Cookie header", () => {
    const h = new Headers({ cookie: "a=1; SESSION_ID=tok-xyz; b=2" });
    expect(readSessionToken(h)).toBe("tok-xyz");
  });

  it("returns undefined when absent or empty", () => {
    expect(readSessionToken(new Headers())).toBeUndefined();
    expect(readSessionToken(new Headers({ cookie: "SESSION_ID=" }))).toBeUndefined();
  });

  it("honours a configured cookie name", () => {
    process.env.SESSION_COOKIE_NAME = "MY_SID";
    expect(buildSessionCookie("t", "2030-01-01T08:00:00Z", now).startsWith("MY_SID=t;")).toBe(true);
    expect(readSessionToken(new Headers({ cookie: "MY_SID=zz" }))).toBe("zz");
  });
});
