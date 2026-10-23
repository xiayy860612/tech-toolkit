import "server-only";

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

/**
 * 同源校验（FR-033、research R13）：所有改状态的请求（非 GET / HEAD / OPTIONS）必须来自本站。
 * - 有 `Sec-Fetch-Site` 时必须是 `same-origin`；
 * - 有 `Origin` 时其主机必须等于请求的公开主机（`X-Forwarded-Host` 第一个值，否则 `Host`）；
 * - 二者都没有则拒绝。
 * `SameSite=Strict` 的 cookie 是第二层防护。
 */
export function isSameOriginRequest(method: string, headers: Headers): boolean {
  if (SAFE_METHODS.has(method.toUpperCase())) return true;

  const site = headers.get("sec-fetch-site");
  const origin = headers.get("origin");
  if (site === null && origin === null) return false;
  if (site !== null && site !== "same-origin") return false;

  if (origin !== null) {
    const host = headers.get("x-forwarded-host")?.split(",")[0].trim() || headers.get("host");
    if (!host) return false;
    let originHost: string;
    try {
      originHost = new URL(origin).host;
    } catch {
      return false; // `null`、无法解析
    }
    if (originHost !== host) return false;
  }
  return true;
}

export function originForbiddenResponse(): Response {
  return new Response("Forbidden", { status: 403, headers: { "Cache-Control": "no-store" } });
}
