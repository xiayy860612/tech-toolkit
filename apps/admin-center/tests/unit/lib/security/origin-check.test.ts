/**
 * @jest-environment node
 */
import { isSameOriginRequest, originForbiddenResponse } from "@/lib/security/origin-check";

const h = (init: Record<string, string>) => new Headers(init);

describe("isSameOriginRequest（FR-033）", () => {
  it.each(["GET", "HEAD", "OPTIONS"])("%s 不改状态，不做校验", (method) => {
    expect(isSameOriginRequest(method, h({ "sec-fetch-site": "cross-site" }))).toBe(true);
  });

  it("POST：Sec-Fetch-Site 为 same-origin 且 Origin 与 Host 一致 → 通过", () => {
    expect(
      isSameOriginRequest(
        "POST",
        h({ host: "admin.example.com", origin: "https://admin.example.com", "sec-fetch-site": "same-origin" }),
      ),
    ).toBe(true);
  });

  it("POST：只有 Origin（没有 Sec-Fetch-Site）且一致 → 通过", () => {
    expect(isSameOriginRequest("POST", h({ host: "localhost:3000", origin: "http://localhost:3000" }))).toBe(true);
  });

  it("POST：只有 Sec-Fetch-Site: same-origin（没有 Origin）→ 通过", () => {
    expect(isSameOriginRequest("POST", h({ host: "a.test", "sec-fetch-site": "same-origin" }))).toBe(true);
  });

  it.each(["cross-site", "same-site", "none"])("POST：Sec-Fetch-Site=%s → 拒绝", (site) => {
    expect(
      isSameOriginRequest("POST", h({ host: "a.test", origin: "https://a.test", "sec-fetch-site": site })),
    ).toBe(false);
  });

  it("POST：Origin 主机与 Host 不同 → 拒绝（跨站伪造）", () => {
    expect(isSameOriginRequest("POST", h({ host: "admin.example.com", origin: "https://evil.example" }))).toBe(false);
  });

  it("POST：子域也算跨源 → 拒绝", () => {
    expect(isSameOriginRequest("POST", h({ host: "admin.example.com", origin: "https://x.admin.example.com" }))).toBe(false);
  });

  it("POST：Origin: null 或无法解析 → 拒绝", () => {
    expect(isSameOriginRequest("POST", h({ host: "a.test", origin: "null" }))).toBe(false);
    expect(isSameOriginRequest("POST", h({ host: "a.test", origin: "::::" }))).toBe(false);
  });

  it("POST：Origin 与 Sec-Fetch-Site 都没有 → 拒绝", () => {
    expect(isSameOriginRequest("POST", h({ host: "a.test" }))).toBe(false);
  });

  it("DELETE 同样校验", () => {
    expect(isSameOriginRequest("DELETE", h({ host: "a.test", origin: "https://evil.test" }))).toBe(false);
  });

  it("在反向代理后用 X-Forwarded-Host（取第一个值）而不是内部 Host", () => {
    const headers = h({
      host: "10.0.0.5:3000",
      "x-forwarded-host": "admin.example.com, proxy.internal",
      origin: "https://admin.example.com",
    });
    expect(isSameOriginRequest("POST", headers)).toBe(true);
    expect(
      isSameOriginRequest("POST", h({ host: "10.0.0.5:3000", "x-forwarded-host": "admin.example.com", origin: "http://10.0.0.5:3000" })),
    ).toBe(false);
  });

  it("没有 Host 也没有转发主机时，带 Origin 的请求拒绝", () => {
    expect(isSameOriginRequest("POST", h({ origin: "https://a.test" }))).toBe(false);
  });
});

describe("originForbiddenResponse", () => {
  it("返回 403，不含内部细节", async () => {
    const res = originForbiddenResponse();
    expect(res.status).toBe(403);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(await res.text()).toBe("Forbidden");
  });
});
