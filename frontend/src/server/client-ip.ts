import { getConfig } from "./config";

export interface ClientIpOptions {
  /** 信任的反向代理层数；0 = 不信任 X-Forwarded-For */
  hops?: number;
  /** 直接连接的对端地址（若运行时能提供） */
  connectionAddress?: string;
}

/**
 * 解析客户端 IP。仅在配置了可信代理层数时才采用 X-Forwarded-For：
 * 取「从右数第 hops 个」条目（由最外层可信代理追加，客户端无法伪造）。
 */
export function getClientIp(headers: Headers, opts: ClientIpOptions = {}): string {
  const hops = opts.hops ?? 0;
  if (hops > 0) {
    const chain = (headers.get("x-forwarded-for") ?? "")
      .split(",")
      .map((p) => p.trim())
      .filter(Boolean);
    if (chain.length >= hops) return chain[chain.length - hops]!;
  }
  return opts.connectionAddress ?? "unknown";
}

export function requestClientIp(request: { headers: Headers }): string {
  return getClientIp(request.headers, { hops: getConfig().trustedProxyHops });
}
