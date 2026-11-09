import RedisMock from 'ioredis-mock';
import { beforeEach, describe, expect, it } from 'vitest';
import { createSessionStore, ttlSecondsUntil } from '@/server/session';

const NOW = Date.parse('2030-01-01T00:00:00Z');

describe('ttlSecondsUntil', () => {
  it('rounds up to whole seconds', () => {
    expect(ttlSecondsUntil('2030-01-01T00:30:00.500Z', NOW)).toBe(1801);
  });

  it('never returns less than one second', () => {
    expect(ttlSecondsUntil('2029-12-31T00:00:00Z', NOW)).toBe(1);
    expect(ttlSecondsUntil('not-a-date', NOW)).toBe(1);
  });
});

describe('SessionStore', () => {
  let redis: InstanceType<typeof RedisMock>;
  let store: ReturnType<typeof createSessionStore>;

  beforeEach(async () => {
    redis = new RedisMock();
    await redis.flushall();
    store = createSessionStore(redis as never, () => NOW);
  });

  const login = {
    token: 'tk_abc',
    expiresAt: '2030-01-01T00:30:00Z',
    mustChangePassword: true,
  };

  it('creates a Session with a random id and stores the Opaque Token with a TTL', async () => {
    const id = await store.create(login);

    expect(id).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(id).not.toContain('tk_');
    const key = `platform:session:${id}`;
    expect(JSON.parse((await redis.get(key))!)).toEqual({
      token: 'tk_abc',
      mustChangePassword: true,
    });
    expect(await redis.ttl(key)).toBe(1800);
  });

  it('generates a different id each time', async () => {
    expect(await store.create(login)).not.toBe(await store.create(login));
  });

  it('reads the Session back', async () => {
    const id = await store.create(login);
    await expect(store.get(id)).resolves.toEqual({ token: 'tk_abc', mustChangePassword: true });
  });

  it('returns null for an unknown id or empty id', async () => {
    await expect(store.get('missing')).resolves.toBeNull();
    await expect(store.get('')).resolves.toBeNull();
  });

  it('returns null when the stored value is corrupt', async () => {
    await redis.set('platform:session:bad', '{not json');
    await expect(store.get('bad')).resolves.toBeNull();
  });

  it('replaces the Opaque Token and flag, resetting the TTL', async () => {
    const id = await store.create(login);

    await store.replace(id, {
      token: 'tk_new',
      expiresAt: '2030-01-01T01:00:00Z',
      mustChangePassword: false,
    });

    await expect(store.get(id)).resolves.toEqual({ token: 'tk_new', mustChangePassword: false });
    expect(await redis.ttl(`platform:session:${id}`)).toBe(3600);
  });

  it('destroys the Session', async () => {
    const id = await store.create(login);
    await store.destroy(id);
    await expect(store.get(id)).resolves.toBeNull();
  });

  it('destroy of an unknown or empty id is a no-op', async () => {
    await expect(store.destroy('missing')).resolves.toBeUndefined();
    await expect(store.destroy('')).resolves.toBeUndefined();
  });

  it('expires the Session when the TTL elapses', async () => {
    const id = await store.create({ ...login, expiresAt: '2030-01-01T00:00:01Z' });
    await redis.expire(`platform:session:${id}`, 0);
    await expect(store.get(id)).resolves.toBeNull();
  });
});
