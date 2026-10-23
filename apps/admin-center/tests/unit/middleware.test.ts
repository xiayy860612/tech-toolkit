/**
 * @jest-environment node
 */
import { NextRequest } from "next/server";
import { config, middleware } from "@/middleware";

function run(url = "http://localhost:3000/users") {
  return middleware(new NextRequest(url));
}

describe("middleware：安全响应头与 CSP nonce", () => {
  it("设置 X-Content-Type-Options、Referrer-Policy", () => {
    const res = run();
    expect(res.headers.get("x-content-type-options")).toBe("nosniff");
    expect(res.headers.get("referrer-policy")).toBe("same-origin");
  });

  it("CSP 含 nonce 与 frame-ancestors 'none'，并禁用 object / 外部 base", () => {
    const csp = run().headers.get("content-security-policy")!;
    expect(csp).toMatch(/script-src [^;]*'nonce-[A-Za-z0-9+/=_-]{16,}'/);
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("base-uri 'self'");
    expect(csp).toContain("form-action 'self'");
    expect(csp).not.toContain("'unsafe-eval'");
    expect(csp).not.toMatch(/script-src[^;]*'unsafe-inline'/);
  });

  it("每个请求的 nonce 不同", () => {
    const nonce = (res: Response) => /'nonce-([^']+)'/.exec(res.headers.get("content-security-policy")!)![1];
    expect(nonce(run())).not.toBe(nonce(run()));
  });

  it("把 nonce 与 CSP 作为请求头转给渲染层（Next 据此给脚本加 nonce）", () => {
    const res = run();
    const overridden = res.headers.get("x-middleware-override-headers")!;
    expect(overridden).toContain("x-nonce");
    expect(overridden).toContain("content-security-policy");
    const nonce = res.headers.get("x-middleware-request-x-nonce")!;
    expect(res.headers.get("content-security-policy")).toContain(`'nonce-${nonce}'`);
  });

  it("开发环境允许 unsafe-eval（React 开发模式需要）", () => {
    const original = process.env.NODE_ENV;
    Object.assign(process.env, { NODE_ENV: "development" });
    try {
      expect(run().headers.get("content-security-policy")).toContain("'unsafe-eval'");
    } finally {
      Object.assign(process.env, { NODE_ENV: original });
    }
  });

  it("不发任何网络请求", () => {
    const spy = jest.spyOn(global, "fetch");
    run();
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });

  it("matcher 排除静态资源", () => {
    expect(config.matcher).toEqual([
      expect.stringContaining("_next/static"),
    ]);
  });
});
