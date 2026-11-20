import { getConfig } from "../config";

/** 令牌来自后端（不透明、Cookie 安全字符集）；拒绝任何可能破坏 Cookie 语法的值。 */
const UNSAFE_COOKIE_VALUE = /[\s;,"\\]/;

function attributes(maxAge: number, secure: boolean): string[] {
  const attrs = ["Path=/", `Max-Age=${maxAge}`, "HttpOnly", "SameSite=Lax"];
  if (secure) attrs.push("Secure");
  return attrs;
}

/** 会话 Cookie：HttpOnly; Secure; SameSite=Lax; Path=/，Max-Age 取自后端 expiresAt。 */
export function buildSessionCookie(
  token: string,
  expiresAt: string | Date,
  now: number = Date.now(),
): string {
  if (!token || UNSAFE_COOKIE_VALUE.test(token)) {
    throw new Error("invalid session token for cookie");
  }
  const cfg = getConfig();
  const until = expiresAt instanceof Date ? expiresAt.getTime() : Date.parse(expiresAt);
  const maxAge = Number.isFinite(until) ? Math.max(0, Math.floor((until - now) / 1000)) : 0;
  return [`${cfg.cookieName}=${token}`, ...attributes(maxAge, cfg.cookieSecure)].join("; ");
}

export function buildClearCookie(): string {
  const cfg = getConfig();
  return [`${cfg.cookieName}=`, ...attributes(0, cfg.cookieSecure)].join("; ");
}

export function readSessionToken(headers: Headers): string | undefined {
  const name = getConfig().cookieName;
  const raw = headers.get("cookie");
  if (!raw) return undefined;
  for (const part of raw.split(";")) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    if (part.slice(0, eq).trim() === name) {
      const value = part.slice(eq + 1).trim();
      return value || undefined;
    }
  }
  return undefined;
}
