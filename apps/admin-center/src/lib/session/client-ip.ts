import "server-only";
import { isIP } from "node:net";

/**
 * 取终端来源地址（登录失败限制的来源维度，必须不可伪造）。
 *
 * `hops` 是 Admin Center 前面可信反向代理的跳数。每个可信代理会把它看到的对端地址追加到
 * `X-Forwarded-For` 末尾，所以真实终端地址是**右数第 hops 项**；它左边的内容可能是用户自带的，一律不采信。
 * 项数不足、`hops` 为 0、或取到的不是合法 IP 时返回 `null`（调用方自行决定如何处理，不要把任意字符串交给 UAM）。
 */
export function getClientIp(headers: Headers, hops: number): string | null {
  if (hops < 1) return null;
  const forwarded = headers.get("x-forwarded-for");
  if (!forwarded) return null;
  const entries = forwarded.split(",").map((s) => s.trim());
  if (entries.length < hops) return null;
  const candidate = entries[entries.length - hops];
  return isIP(candidate) ? candidate : null;
}
