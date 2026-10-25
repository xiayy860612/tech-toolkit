import "server-only";

/** 登录后没有（安全的）`next` 时进入的默认页面。 */
export const DEFAULT_NEXT_PATH = "/users";

const MAX_LENGTH = 2048;

function hasBackslashOrControlChar(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (code === 0x5c || code <= 0x1f || code === 0x7f) return true;
  }
  return false;
}

/**
 * `next` 白名单（FR-012）：只接受**以单个 `/` 开头的站内路径**。
 * 拒绝：`//` 开头（协议相对地址）、带协议、含反斜杠（浏览器会把 `\` 当 `/`）、含控制字符（头注入）、过长、
 * 以及登录页自身（登录后又回登录页没有意义）。
 */
export function isSafeNextPath(raw: unknown): raw is string {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > MAX_LENGTH) return false;
  if (!raw.startsWith("/") || raw.startsWith("//")) return false;
  if (hasBackslashOrControlChar(raw)) return false;
  if (/^\/login(?:[/?#]|$)/.test(raw)) return false;
  // 最后一道保险：按 URL 规则解析后必须仍在本站
  return new URL(raw, "http://site.invalid").origin === "http://site.invalid";
}

/** 取安全的 `next`；缺失或不安全一律回到 `/users`。 */
export function safeNextPath(raw: unknown): string {
  return isSafeNextPath(raw) ? raw : DEFAULT_NEXT_PATH;
}
