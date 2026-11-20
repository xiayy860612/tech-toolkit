// @vitest-environment node
import { NextRequest } from "next/server";
import { beforeEach, describe, expect, it } from "vitest";
import { config, middleware } from "@/middleware";
import { TEST_ENV_DEFAULTS } from "@tests/helpers/bff-env";

beforeEach(() => {
  Object.assign(process.env, TEST_ENV_DEFAULTS);
});

const req = (path: string, cookie?: string) =>
  new NextRequest(`http://app.test${path}`, { headers: cookie ? { cookie } : {} });

describe("middleware", () => {
  it("redirects to /login?next=<original> without a session cookie", () => {
    const res = middleware(req("/users?page=2"));
    expect(res.status).toBe(307);
    expect(
      new URL(res.headers.get("location")!).pathname + new URL(res.headers.get("location")!).search,
    ).toBe("/login?next=%2Fusers%3Fpage%3D2");
  });

  it("redirects / without a cookie to a plain /login", () => {
    const res = middleware(req("/"));
    expect(new URL(res.headers.get("location")!).search).toBe("");
    expect(new URL(res.headers.get("location")!).pathname).toBe("/login");
  });

  it("lets /login through without a cookie", () => {
    const res = middleware(req("/login"));
    expect(res.headers.get("location")).toBeNull();
  });

  it("lets requests with a session cookie through and exposes the pathname to the layout", () => {
    const res = middleware(req("/sessions", "SESSION_ID=abc"));
    expect(res.headers.get("location")).toBeNull();
    expect(res.headers.get("x-middleware-request-x-pathname")).toBe("/sessions");
  });

  it("does not run for API routes or static assets", () => {
    const [pattern] = config.matcher;
    const re = new RegExp(`^${pattern}$`);
    expect(re.test("/users")).toBe(true);
    expect(re.test("/api/v1/users")).toBe(false);
    expect(re.test("/_next/static/chunk.js")).toBe(false);
    expect(re.test("/favicon.ico")).toBe(false);
  });
});
