// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';

const redisCtor = vi.hoisted(() => vi.fn());
vi.mock('ioredis', () => ({
  Redis: class {
    constructor(...args: unknown[]) {
      redisCtor(...args);
    }
    set = vi.fn();
    get = vi.fn();
    del = vi.fn();
  },
}));

import { getSessionStore, getTenantCoreClient } from '@/server/container';

describe('container', () => {
  it('creates the Session store once, connected to REDIS_URL', () => {
    const first = getSessionStore();
    expect(getSessionStore()).toBe(first);
    expect(redisCtor).toHaveBeenCalledTimes(1);
    expect(redisCtor.mock.calls[0][0]).toBe('redis://localhost:6379');
  });

  it('creates the tenant-core-api client once', () => {
    const first = getTenantCoreClient();
    expect(getTenantCoreClient()).toBe(first);
    expect(typeof first.login).toBe('function');
  });
});
