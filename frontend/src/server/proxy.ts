import { backendFetch } from "./backend/client";
import { requestClientIp } from "./client-ip";
import { isSameOriginRequest } from "./origin";
import { problemResponse } from "./problem";
import { buildClearCookie, readSessionToken } from "./session/cookie";
import { invalidateResolved, resolveSession } from "./session/resolver";

/** 仅供 BFF 以 typ=service 调用的后端端点，浏览器永远不能经代理触达。 */
const SERVICE_ONLY = new Set(["auth/login", "auth/sessions/resolve"]);
const TENANT_KEYS = new Set(["tenantid", "tenant_id"]);
const PASSTHROUGH_RESPONSE_HEADERS = ["content-type", "retry-after"];

function stripTenant(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripTenant);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .filter(([k]) => !TENANT_KEYS.has(k.toLowerCase()))
        .map(([k, v]) => [k, stripTenant(v)]),
    );
  }
  return value;
}

async function forwardedBody(request: Request): Promise<string | undefined> {
  if (["GET", "HEAD"].includes(request.method.toUpperCase())) return undefined;
  const raw = await request.text();
  if (!raw) return undefined;
  if (!(request.headers.get("content-type") ?? "").includes("json")) return raw;
  try {
    return JSON.stringify(stripTenant(JSON.parse(raw)));
  } catch {
    return raw;
  }
}

function forwardedSearch(request: Request): string {
  const params = new URL(request.url).searchParams;
  for (const key of [...params.keys()]) {
    if (TENANT_KEYS.has(key.toLowerCase())) params.delete(key);
  }
  const qs = params.toString();
  return qs ? `?${qs}` : "";
}

/**
 * 通用代理：Cookie → resolve → 签发 typ=user JWT → 转发后端。
 * BFF 不持有会话状态；租户只取自 resolve 返回的会话身份。
 */
export async function handleProxy(request: Request, segments: string[]): Promise<Response> {
  if (segments.some((s) => s === "." || s === ".." || s === "")) {
    return problemResponse(400, "BAD_REQUEST");
  }
  const path = segments.join("/");
  if (SERVICE_ONLY.has(path)) return problemResponse(404, "NOT_FOUND");

  if (!isSameOriginRequest(request)) return problemResponse(403, "CROSS_SITE_REQUEST");

  const token = readSessionToken(request.headers);
  if (!token) return problemResponse(401, "UNAUTHORIZED");

  const cip = requestClientIp(request);
  try {
    const identity = await resolveSession(token, cip);
    if (!identity) {
      return problemResponse(401, "UNAUTHORIZED", {
        headers: { "set-cookie": buildClearCookie() },
      });
    }

    const upstream = await backendFetch(
      `/${segments.map(encodeURIComponent).join("/")}${forwardedSearch(request)}`,
      {
        method: request.method,
        body: await forwardedBody(request),
        auth: { kind: "user", identity },
        cip,
        headers: {
          accept: request.headers.get("accept") ?? "application/json",
          ...(request.headers.get("content-type")
            ? { "content-type": request.headers.get("content-type")! }
            : {}),
        },
      },
    );

    const headers = new Headers({ "cache-control": "no-store" });
    for (const name of PASSTHROUGH_RESPONSE_HEADERS) {
      const v = upstream.headers.get(name);
      if (v) headers.set(name, v);
    }
    if (upstream.status === 401) {
      invalidateResolved(token);
      headers.append("set-cookie", buildClearCookie());
    }
    const bodiless = upstream.status === 204 || upstream.status === 304;
    return new Response(bodiless ? null : upstream.body, { status: upstream.status, headers });
  } catch (error) {
    // 只记录错误类别，绝不记录令牌/JWT/请求体
    console.error("[bff] upstream failure:", error instanceof Error ? error.name : "unknown");
    return problemResponse(502, "BACKEND_UNAVAILABLE");
  }
}
