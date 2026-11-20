import { getConfig } from "../config";
import { signServiceJwt, signUserJwt, type UserIdentity } from "./jwt";

export type BackendAuth = { kind: "user"; identity: UserIdentity } | { kind: "service" };

export interface BackendRequest {
  method?: string;
  body?: string | null;
  auth: BackendAuth;
  /** 可信客户端 IP，写入 JWT 的 cip */
  cip: string;
  /** 调用方给出的头；只放行白名单，绝不转发浏览器的 Cookie / Authorization / 租户头 */
  headers?: Record<string, string>;
}

const FORWARDED_HEADERS = ["accept", "content-type"];

/** 以 BFF 自己签发的 JWT 调用后端；后端 problem+json 原样返回给调用方处理。 */
export async function backendFetch(path: string, req: BackendRequest): Promise<Response> {
  const cfg = getConfig();
  const token =
    req.auth.kind === "user"
      ? await signUserJwt(req.auth.identity, req.cip)
      : await signServiceJwt(req.cip);

  const headers = new Headers();
  for (const [name, value] of Object.entries(req.headers ?? {})) {
    if (FORWARDED_HEADERS.includes(name.toLowerCase())) headers.set(name, value);
  }
  headers.set("authorization", `Bearer ${token}`);

  return fetch(`${cfg.backendUrl}${path}`, {
    method: req.method ?? "GET",
    headers,
    body: req.body ?? undefined,
    signal: AbortSignal.timeout(cfg.backendTimeoutMs),
    cache: "no-store",
    redirect: "manual",
  });
}
