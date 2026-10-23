import "server-only";
import { headers } from "next/headers";
import { getConfig } from "@/lib/config";
import { UamError, type FieldError } from "./errors";
import type { components } from "./types.generated";

export type VerifyResponse = components["schemas"]["VerifyResponse"];

/** 操作人上下文：作为 X-Operator-* 请求头传给 UAM（写操作必带）。 */
export interface Operator {
  userId: string;
  accountId: string;
  sessionRef: string;
}

export interface UamRequestInit {
  method: "GET" | "POST" | "DELETE";
  /** 以 `/v1/...` 开头的路径（已替换好路径参数）。 */
  path: string;
  query?: Record<string, string | number | undefined>;
  body?: unknown;
  operator?: Operator;
  /** 额外请求头，如 Idempotency-Key。 */
  headers?: Record<string, string>;
  /** 缺省取当前 HTTP 请求的 traceparent。 */
  traceparent?: string;
}

export interface UamResponse<T> {
  status: number;
  data: T;
}

interface Problem {
  code?: string;
  errors?: FieldError[];
}

async function currentTraceparent(): Promise<string | undefined> {
  try {
    return (await headers()).get("traceparent") ?? undefined;
  } catch {
    // 不在请求作用域内（例如启动脚本），没有可传递的 traceparent
    return undefined;
  }
}

async function readProblem(res: Response): Promise<Problem | undefined> {
  try {
    const body = await res.json();
    return body && typeof body === "object" && typeof body.code === "string" ? (body as Problem) : undefined;
  } catch {
    return undefined;
  }
}

/**
 * 调用 UAM。每个请求都带服务密钥；失败一律抛 `UamError`，按 kind 区分「业务结论 / 不可用 / 配置错误」。
 * 密钥、会话标识与密码不进入错误信息与日志。
 */
export async function uamRequest<T = unknown>(init: UamRequestInit): Promise<UamResponse<T>> {
  const config = getConfig();
  const url = new URL(config.uamBaseUrl + init.path);
  for (const [k, v] of Object.entries(init.query ?? {})) {
    if (v !== undefined) url.searchParams.set(k, String(v));
  }

  const requestHeaders: Record<string, string> = {
    Authorization: `Bearer ${config.uamServiceKey}`,
    Accept: "application/json",
    ...init.headers,
  };
  if (init.body !== undefined) requestHeaders["Content-Type"] = "application/json";
  if (init.operator) {
    requestHeaders["X-Operator-User-Id"] = init.operator.userId;
    requestHeaders["X-Operator-Account-Id"] = init.operator.accountId;
    requestHeaders["X-Operator-Session-Ref"] = init.operator.sessionRef;
  }
  const traceparent = init.traceparent ?? (await currentTraceparent());
  if (traceparent) requestHeaders.traceparent = traceparent;

  let res: Response;
  try {
    res = await fetch(url, {
      method: init.method,
      headers: requestHeaders,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      cache: "no-store",
      signal: AbortSignal.timeout(config.uamTimeoutMs),
    });
  } catch {
    throw new UamError("unavailable", "UAM 请求超时或连接失败");
  }

  if (res.ok) {
    if (res.status === 204) return { status: 204, data: undefined as T };
    try {
      return { status: res.status, data: (await res.json()) as T };
    } catch {
      throw new UamError("unavailable", "UAM 响应无法解析", { status: res.status });
    }
  }

  if (res.status >= 500) throw new UamError("unavailable", `UAM 返回 ${res.status}`, { status: res.status });

  const problem = await readProblem(res);
  const code = problem?.code;
  // 服务密钥缺失 / 无效、客户端无权限：UAM 拒绝的是 Admin Center，不是用户会话
  const rejectedClient =
    res.status === 403 || (res.status === 401 && code !== "session_invalid" && code !== "invalid_credentials");
  if (rejectedClient) {
    throw new UamError("misconfigured", `UAM 拒绝了服务调用（${res.status}）`, { status: res.status, code });
  }
  if (!problem) throw new UamError("unavailable", `UAM 返回 ${res.status}`, { status: res.status });

  const retryAfter = Number(res.headers.get("retry-after"));
  throw new UamError("problem", `UAM 业务错误：${code}`, {
    status: res.status,
    code,
    fieldErrors: problem.errors,
    retryAfter: Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
  });
}

/** 校验会话标识并取得会话数据（`POST /v1/auth/sessions/verify`）。 */
export async function verifySession(sessionId: string): Promise<VerifyResponse> {
  const { data } = await uamRequest<VerifyResponse>({
    method: "POST",
    path: "/v1/auth/sessions/verify",
    body: { sessionId },
  });
  return data;
}
