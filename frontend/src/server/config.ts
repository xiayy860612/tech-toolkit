/**
 * BFF 运行配置。每次调用都读取环境变量（不缓存），便于测试，且生产环境变量在进程生命周期内不变。
 * 仅服务端使用——不要在客户端组件中导入。
 */
export interface BffConfig {
  backendUrl: string;
  backendTimeoutMs: number;
  redisUrl: string;
  defaultTenantId: number;
  cookieName: string;
  cookieSecure: boolean;
  resolveCacheTtlSeconds: number;
  jwtPrivateKeyPath: string;
  jwtIssuer: string;
  jwtAudience: string;
  jwtTtlSeconds: number;
  trustedProxyHops: number;
  loginRateLimitMax: number;
  loginRateLimitWindowSeconds: number;
}

function int(value: string | undefined, fallback: number): number {
  const n = Number.parseInt(value ?? "", 10);
  return Number.isFinite(n) ? n : fallback;
}

export function getConfig(env: NodeJS.ProcessEnv = process.env): BffConfig {
  return {
    backendUrl: (env.BACKEND_URL ?? "http://localhost:8080").replace(/\/+$/, ""),
    backendTimeoutMs: int(env.BACKEND_TIMEOUT_MS, 10_000),
    redisUrl: env.REDIS_URL ?? "redis://localhost:6379",
    defaultTenantId: int(env.DEFAULT_TENANT_ID, 0),
    cookieName: env.SESSION_COOKIE_NAME || "SESSION_ID",
    cookieSecure: env.COOKIE_SECURE !== "false",
    resolveCacheTtlSeconds: Math.max(0, int(env.SESSION_RESOLVE_CACHE_TTL_SECONDS, 5)),
    jwtPrivateKeyPath: env.INTERNAL_JWT_PRIVATE_KEY_PATH ?? "./.keys/internal-jwt-private.jwk.json",
    jwtIssuer: env.INTERNAL_JWT_ISSUER ?? "admin-center-bff",
    jwtAudience: env.INTERNAL_JWT_AUDIENCE ?? "tenant-core-api",
    jwtTtlSeconds: int(env.INTERNAL_JWT_TTL_SECONDS, 60),
    trustedProxyHops: Math.max(0, int(env.TRUSTED_PROXY_HOPS, 0)),
    loginRateLimitMax: int(env.LOGIN_RATE_LIMIT_MAX, 20),
    loginRateLimitWindowSeconds: int(env.LOGIN_RATE_LIMIT_WINDOW_SECONDS, 60),
  };
}
