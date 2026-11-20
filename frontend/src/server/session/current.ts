import type { Me } from "@/lib/api/types";
import { backendFetch } from "../backend/client";
import { getClientIp } from "../client-ip";
import { getConfig } from "../config";
import { readSessionToken } from "./cookie";
import { resolveSession } from "./resolver";

/**
 * 服务端组件用：Cookie → resolve → 以用户 JWT 取 `/auth/me`。
 * 角色以 `/auth/me`（数据库当前值）为准。无有效会话返回 null。
 */
export async function getCurrentSession(headers: Headers): Promise<Me | null> {
  const token = readSessionToken(headers);
  if (!token) return null;

  const cip = getClientIp(headers, { hops: getConfig().trustedProxyHops });
  const identity = await resolveSession(token, cip);
  if (!identity) return null;

  const res = await backendFetch("/auth/me", {
    auth: { kind: "user", identity },
    cip,
    headers: { accept: "application/json" },
  });
  if (res.status === 401) return null;
  if (!res.ok) throw new Error(`/auth/me failed with status ${res.status}`);
  return (await res.json()) as Me;
}
