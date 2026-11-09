import { randomBytes } from 'node:crypto';
import type { Redis } from 'ioredis';
import type { LoginResponse } from '@/lib/types';

export interface StoredSession {
  /** Opaque Token. Never leaves the server. */
  token: string;
  mustChangePassword: boolean;
}

const KEY_PREFIX = 'platform:session:';

/** Redis TTL aligned with the Opaque Token expiry; at least one second. */
export function ttlSecondsUntil(expiresAt: string, now: number = Date.now()): number {
  const ms = Date.parse(expiresAt) - now;
  return Number.isFinite(ms) ? Math.max(1, Math.ceil(ms / 1000)) : 1;
}

export function createSessionStore(redis: Redis, clock: () => number = Date.now) {
  const key = (id: string) => `${KEY_PREFIX}${id}`;

  async function write(id: string, login: LoginResponse): Promise<void> {
    const value: StoredSession = {
      token: login.token,
      mustChangePassword: login.mustChangePassword,
    };
    await redis.set(
      key(id),
      JSON.stringify(value),
      'EX',
      ttlSecondsUntil(login.expiresAt, clock()),
    );
  }

  return {
    /** Stores the Opaque Token and returns the random Session id used as cookie value. */
    async create(login: LoginResponse): Promise<string> {
      const id = randomBytes(32).toString('base64url');
      await write(id, login);
      return id;
    },

    async get(id: string): Promise<StoredSession | null> {
      if (!id) return null;
      const raw = await redis.get(key(id));
      if (!raw) return null;
      try {
        return JSON.parse(raw) as StoredSession;
      } catch {
        return null;
      }
    },

    /** Swaps in a new Opaque Token (after a password change) under the same Session id. */
    replace: write,

    async destroy(id: string): Promise<void> {
      if (!id) return;
      await redis.del(key(id));
    },
  };
}

export type SessionStore = ReturnType<typeof createSessionStore>;
