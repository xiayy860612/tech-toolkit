import { backendFetch } from "@/server/backend/client";
import { requestClientIp } from "@/server/client-ip";
import { isSameOriginRequest } from "@/server/origin";
import { problemResponse } from "@/server/problem";
import { buildClearCookie, readSessionToken } from "@/server/session/cookie";
import { invalidateResolved, resolveSession } from "@/server/session/resolver";

export const dynamic = "force-dynamic";

/** BFF 自有路由：让后端撤销会话并记审计，然后清除 Cookie；无会话时幂等成功。 */
export async function POST(request: Request): Promise<Response> {
  if (!isSameOriginRequest(request)) return problemResponse(403, "CROSS_SITE_REQUEST");

  const clear = { "set-cookie": buildClearCookie() };
  const token = readSessionToken(request.headers);
  if (!token) return new Response(null, { status: 204, headers: clear });

  const cip = requestClientIp(request);
  try {
    const identity = await resolveSession(token, cip);
    if (identity) {
      const res = await backendFetch("/auth/logout", {
        method: "POST",
        auth: { kind: "user", identity },
        cip,
      });
      // 401 = 后端已判定会话无效，同样视为已退出
      if (res.status >= 500) return problemResponse(502, "BACKEND_UNAVAILABLE", { headers: clear });
    }
    invalidateResolved(token);
    return new Response(null, { status: 204, headers: clear });
  } catch (error) {
    console.error(
      "[bff] logout upstream failure:",
      error instanceof Error ? error.name : "unknown",
    );
    return problemResponse(502, "BACKEND_UNAVAILABLE", { headers: clear });
  }
}
