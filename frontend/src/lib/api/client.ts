import { loginUrlFor } from "../navigation";
import { ApiError, type FieldError } from "./errors";

const API_BASE = "/api/v1";

export interface ApiOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  signal?: AbortSignal;
}

export { loginUrlFor };

function redirectToLogin(): void {
  window.location.assign(loginUrlFor(window.location.pathname, window.location.search));
}

let onSessionExpired: () => void = redirectToLogin;

export function setSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler;
}

async function toApiError(res: Response): Promise<ApiError> {
  try {
    const problem = (await res.json()) as {
      code?: string;
      errors?: FieldError[];
      title?: string;
    };
    return new ApiError(res.status, problem.code ?? "UNKNOWN", problem.errors ?? [], problem.title);
  } catch {
    return new ApiError(res.status, "UNKNOWN");
  }
}

/**
 * 浏览器端 fetch 封装：只访问同源 /api/v1；problem+json → ApiError(code)；
 * 任一接口 401（登录接口除外）→ 触发会话过期处理（跳转 /login?next=…）。
 */
export async function apiRequest<T = unknown>(path: string, opts: ApiOptions = {}): Promise<T> {
  if (
    !path.startsWith("/") ||
    path.startsWith("//") ||
    path.includes("..") ||
    path.includes("://")
  ) {
    throw new Error(`invalid API path: ${path}`);
  }
  const hasBody = opts.body !== undefined;
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      method: opts.method ?? "GET",
      headers: {
        accept: "application/json",
        ...(hasBody ? { "content-type": "application/json" } : {}),
      },
      body: hasBody ? JSON.stringify(opts.body) : undefined,
      credentials: "same-origin",
      signal: opts.signal,
    });
  } catch {
    throw new ApiError(0, "NETWORK_ERROR");
  }

  if (!res.ok) {
    const error = await toApiError(res);
    if (res.status === 401 && !path.startsWith("/auth/login")) onSessionExpired();
    throw error;
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => apiRequest<T>(path, { signal }),
  post: <T>(path: string, body?: unknown) => apiRequest<T>(path, { method: "POST", body }),
  patch: <T>(path: string, body?: unknown) => apiRequest<T>(path, { method: "PATCH", body }),
  delete: <T = void>(path: string) => apiRequest<T>(path, { method: "DELETE" }),
};
