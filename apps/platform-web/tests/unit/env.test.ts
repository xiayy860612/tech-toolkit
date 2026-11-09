import { describe, expect, it } from 'vitest';
import { getEnv } from '@/server/env';

describe('getEnv', () => {
  it('uses local defaults', () => {
    expect(getEnv({})).toEqual({
      tenantCoreApiUrl: 'http://localhost:8080',
      redisUrl: 'redis://localhost:6379',
      cookieSecure: false,
    });
  });

  it('reads configured values', () => {
    expect(
      getEnv({
        TENANT_CORE_API_URL: 'http://api:8080',
        REDIS_URL: 'redis://redis:6379',
      }),
    ).toMatchObject({ tenantCoreApiUrl: 'http://api:8080', redisUrl: 'redis://redis:6379' });
  });

  it('marks the cookie Secure in production by default', () => {
    expect(getEnv({ NODE_ENV: 'production' }).cookieSecure).toBe(true);
  });

  it('allows SESSION_COOKIE_SECURE to override the default', () => {
    expect(getEnv({ NODE_ENV: 'production', SESSION_COOKIE_SECURE: 'false' }).cookieSecure).toBe(
      false,
    );
    expect(getEnv({ SESSION_COOKIE_SECURE: 'true' }).cookieSecure).toBe(true);
  });
});
