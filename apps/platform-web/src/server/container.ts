import { Redis } from 'ioredis';
import { getEnv } from './env';
import { createSessionStore, type SessionStore } from './session';
import { createTenantCoreClient, type TenantCoreClient } from './tenantCoreClient';

// Lazily created, process-wide singletons (the BFF itself is stateless).
let sessionStore: SessionStore | undefined;
let client: TenantCoreClient | undefined;

export function getSessionStore(): SessionStore {
  sessionStore ??= createSessionStore(new Redis(getEnv().redisUrl, { lazyConnect: false }));
  return sessionStore;
}

export function getTenantCoreClient(): TenantCoreClient {
  client ??= createTenantCoreClient({ baseUrl: getEnv().tenantCoreApiUrl });
  return client;
}
