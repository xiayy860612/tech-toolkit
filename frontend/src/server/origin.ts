const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

/**
 * 非 GET/HEAD 请求的来源校验（research R5）：Sec-Fetch-Site 必须为 same-origin，
 * 且 Origin 的主机与本服务一致（兼容反向代理转发的 Host / X-Forwarded-Host）。
 */
export function isSameOriginRequest(request: Request): boolean {
  if (SAFE_METHODS.has(request.method.toUpperCase())) return true;
  if (request.headers.get("sec-fetch-site") !== "same-origin") return false;

  const origin = request.headers.get("origin");
  if (!origin) return false;
  let originHost: string;
  try {
    originHost = new URL(origin).host;
  } catch {
    return false;
  }
  const hosts = [
    request.headers.get("x-forwarded-host")?.split(",")[0]?.trim(),
    request.headers.get("host"),
    new URL(request.url).host,
  ];
  return hosts.some((h) => h === originHost);
}
