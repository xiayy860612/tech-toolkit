import { createHash } from "node:crypto";
import { backendFetch } from "../backend/client";
import { getConfig } from "../config";

/** 后端 resolve 返回的会话身份（契约 SessionResolveResult）。BFF 不持久化它。 */
export interface SessionIdentity {
  sessionId: string;
  userId: string;
  accountId: string;
  tenantId: number;
  displayName: string;
  mustChangePassword: boolean;
  expiresAt: string;
}

const MAX_ENTRIES = 5_000;
const cache = new Map<string, { value: SessionIdentity; until: number }>();

const keyOf = (token: string) => createHash("sha256").update(token).digest("hex");

export function clearResolveCache(): void {
  cache.clear();
}

export function invalidateResolved(token: string): void {
  cache.delete(keyOf(token));
}

function prune(now: number): void {
  if (cache.size < MAX_ENTRIES) return;
  for (const [k, v] of cache) if (v.until <= now) cache.delete(k);
  if (cache.size >= MAX_ENTRIES) cache.clear();
}

/**
 * Cookie 里的令牌 → 后端 resolve。仅在内存做 ≤ TTL 的短缓存（缓存只作用于 resolve 层，
 * 后端每次 API 调用仍按 sid 校验，撤销不会被放大）。401 视为无会话；其它失败抛错。
 */
export async function resolveSession(token: string, cip: string): Promise<SessionIdentity | null> {
  const ttlMs = getConfig().resolveCacheTtlSeconds * 1000;
  const key = keyOf(token);
  const now = Date.now();

  if (ttlMs > 0) {
    const hit = cache.get(key);
    if (hit && hit.until > now) return hit.value;
  }

  const res = await backendFetch("/auth/sessions/resolve", {
    method: "POST",
    body: JSON.stringify({ sessionToken: token }),
    headers: { "content-type": "application/json", accept: "application/json" },
    auth: { kind: "service" },
    cip,
  });

  if (res.status === 401) {
    cache.delete(key);
    return null;
  }
  if (!res.ok) {
    throw new Error(`session resolve failed with status ${res.status}`);
  }

  const value = (await res.json()) as SessionIdentity;
  if (ttlMs > 0) {
    prune(now);
    cache.set(key, { value, until: now + ttlMs });
  }
  return value;
}
