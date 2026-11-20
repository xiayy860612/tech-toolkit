import { backendFetch } from "@/server/backend/client";
import { requestClientIp } from "@/server/client-ip";
import { getConfig } from "@/server/config";
import { isSameOriginRequest } from "@/server/origin";
import { problemResponse } from "@/server/problem";
import { createRateLimiter } from "@/server/rate-limit";
import { getRedis } from "@/server/redis";
import { buildSessionCookie } from "@/server/session/cookie";

export const dynamic = "force-dynamic";

interface Credentials {
  username: string;
  password: string;
}

async function readCredentials(request: Request): Promise<Credentials | null> {
  try {
    const body = (await request.json()) as Partial<Record<keyof Credentials, unknown>>;
    const { username, password } = body;
    if (
      typeof username === "string" &&
      typeof password === "string" &&
      username.length >= 1 &&
      username.length <= 64 &&
      password.length >= 1 &&
      password.length <= 128
    ) {
      return { username, password };
    }
  } catch {
    // fallthrough
  }
  return null;
}

/** BFF 自有路由：先限流，再以 typ=service JWT 调后端登录，成功后设置会话 Cookie。 */
export async function POST(request: Request): Promise<Response> {
  if (!isSameOriginRequest(request)) return problemResponse(403, "CROSS_SITE_REQUEST");

  const cfg = getConfig();
  const cip = requestClientIp(request);

  try {
    const limit = await createRateLimiter(getRedis()).check("login", cip, {
      max: cfg.loginRateLimitMax,
      windowSeconds: cfg.loginRateLimitWindowSeconds,
    });
    if (!limit.allowed) {
      return problemResponse(429, "TOO_MANY_REQUESTS", {
        headers: { "retry-after": String(limit.retryAfterSeconds) },
      });
    }
  } catch (error) {
    // 限流存储不可用时放行（宁可降级也不让所有人无法登录）；只记录错误类别
    console.error(
      "[bff] rate limiter unavailable:",
      error instanceof Error ? error.name : "unknown",
    );
  }

  const credentials = await readCredentials(request);
  if (!credentials) return problemResponse(400, "VALIDATION_FAILED");

  try {
    const upstream = await backendFetch("/auth/login", {
      method: "POST",
      body: JSON.stringify({
        ...credentials,
        userAgent: request.headers.get("user-agent")?.slice(0, 512) ?? null,
      }),
      headers: { "content-type": "application/json", accept: "application/json" },
      auth: { kind: "service" },
      cip,
    });

    if (!upstream.ok) {
      // 后端的失败（含统一的 401 INVALID_CREDENTIALS）原样返回，且绝不设置 Cookie
      return new Response(await upstream.text(), {
        status: upstream.status,
        headers: {
          "content-type": upstream.headers.get("content-type") ?? "application/problem+json",
          "cache-control": "no-store",
        },
      });
    }

    const { me, sessionToken, expiresAt } = (await upstream.json()) as {
      me: { userId: string; accountId: string; displayName: string; mustChangePassword: boolean };
      sessionToken: string;
      expiresAt: string;
    };
    const headers = new Headers({
      "content-type": "application/json",
      "cache-control": "no-store",
    });
    headers.append("set-cookie", buildSessionCookie(sessionToken, expiresAt));
    // 只返回浏览器需要的字段：不含 sessionToken / tenantId / role 等内部字段
    return new Response(
      JSON.stringify({
        userId: me.userId,
        accountId: me.accountId,
        displayName: me.displayName,
        mustChangePassword: me.mustChangePassword,
      }),
      { status: 200, headers },
    );
  } catch (error) {
    console.error("[bff] login upstream failure:", error instanceof Error ? error.name : "unknown");
    return problemResponse(502, "BACKEND_UNAVAILABLE");
  }
}
