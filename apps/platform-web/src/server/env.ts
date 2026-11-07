export interface Env {
  tenantCoreApiUrl: string;
  redisUrl: string;
  cookieSecure: boolean;
}

type Source = Record<string, string | undefined>;

export function getEnv(source: Source = process.env): Env {
  const secureOverride = source.SESSION_COOKIE_SECURE;
  return {
    tenantCoreApiUrl: source.TENANT_CORE_API_URL ?? 'http://localhost:8080',
    redisUrl: source.REDIS_URL ?? 'redis://localhost:6379',
    cookieSecure:
      secureOverride !== undefined ? secureOverride === 'true' : source.NODE_ENV === 'production',
  };
}
