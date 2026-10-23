import "server-only";
import { headers } from "next/headers";
import { getConfig } from "@/lib/config";
import { UamError, type FieldError } from "./errors";
import type { components } from "./types.generated";

export type VerifyResponse = components["schemas"]["VerifyResponse"];
export type LoginRequest = components["schemas"]["LoginRequest"];
export type LoginResponse = components["schemas"]["LoginResponse"];
export type UserSummary = components["schemas"]["UserSummary"];
export type UserPage = components["schemas"]["UserPage"];
export type UserDetail = components["schemas"]["UserDetail"];
export type CreateUserRequest = components["schemas"]["CreateUserRequest"];
export type Account = components["schemas"]["Account"];
export type OnlineUser = components["schemas"]["OnlineUser"];
export type OnlineUserPage = components["schemas"]["OnlineUserPage"];
export type UserSession = components["schemas"]["Session"];

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

/**
 * 账号名 / 密码登录（`POST /v1/auth/login`）。`clientIp` 是终端来源地址（登录失败限制的来源维度），
 * 由调用方从可信代理头取得。响应里的 `sessionId` 只用来写 cookie，不得进入日志或浏览器端组件。
 */
export async function login(request: LoginRequest): Promise<LoginResponse> {
  const { data } = await uamRequest<LoginResponse>({ method: "POST", path: "/v1/auth/login", body: request });
  return data;
}

export interface ListUsersParams {
  /** 按用户名或账号名搜索；空串视为不搜索。 */
  query?: string;
  /** 页码，从 1 开始；缺省由 UAM 取 1。 */
  page?: number;
  /** 每页条数；缺省由 UAM 取 20。 */
  size?: number;
}

/** 分页列出用户，含搜索（`GET /v1/users`）。读操作：不带操作人请求头。 */
export async function listUsers({ query, page, size }: ListUsersParams = {}): Promise<UserPage> {
  const { data } = await uamRequest<UserPage>({
    method: "GET",
    path: "/v1/users",
    query: { query: query || undefined, page, size },
  });
  return data;
}

/** 用户详情及其关联的全部账号（`GET /v1/users/{userId}`）；不存在时抛 `UamError`（problem `user_not_found`）。 */
export async function getUser(userId: string): Promise<UserDetail> {
  const { data } = await uamRequest<UserDetail>({ method: "GET", path: `/v1/users/${encodeURIComponent(userId)}` });
  return data;
}

export interface ListOnlineUsersParams {
  /** 页码，从 1 开始；缺省由 UAM 取 1。 */
  page?: number;
  /** 每页条数；缺省由 UAM 取 20。 */
  size?: number;
}

/** 分页列出当前在线用户，按最近活动倒序（`GET /v1/online-users`）。读操作：不带操作人请求头。 */
export async function listOnlineUsers({ page, size }: ListOnlineUsersParams = {}): Promise<OnlineUserPage> {
  const { data } = await uamRequest<OnlineUserPage>({ method: "GET", path: "/v1/online-users", query: { page, size } });
  return data;
}

/**
 * 某用户当前的全部有效会话（`GET /v1/users/{userId}/sessions`）；用户不存在时抛 `UamError`（problem `user_not_found`）。
 * 带操作人请求头：UAM 据 `X-Operator-Session-Ref` 标注 `current`。响应只含非机密的会话引用。
 */
export async function listUserSessions(userId: string, operator: Operator): Promise<UserSession[]> {
  const { data } = await uamRequest<{ items: UserSession[] }>({
    method: "GET",
    path: `/v1/users/${encodeURIComponent(userId)}/sessions`,
    operator,
  });
  return data.items;
}

/**
 * 创建用户及其一个「账号名 / 密码」账号（`POST /v1/users`）。写操作：带操作人请求头与 `Idempotency-Key`
 * （每次表单渲染一个；UAM 在 10 分钟内对同键重放返回原结果，防双击与超时重试重复创建）。
 * 失败抛 `UamError`：409 `account_name_taken`、422 `validation_failed`（含字段级 `errors`）等；错误信息不含密码。
 */
export async function createUser(request: CreateUserRequest, operator: Operator, idempotencyKey: string): Promise<UserDetail> {
  const { data } = await uamRequest<UserDetail>({
    method: "POST",
    path: "/v1/users",
    body: request,
    operator,
    headers: { "Idempotency-Key": idempotencyKey },
  });
  return data;
}
