// @vitest-environment node
import RedisMock from "ioredis-mock";
import { beforeEach, describe, expect, it } from "vitest";
import { getClientIp } from "@/server/client-ip";
import { createRateLimiter } from "@/server/rate-limit";

const opts = { max: 2, windowSeconds: 60 };
let redis: InstanceType<typeof RedisMock>;
beforeEach(async () => {
  redis = new RedisMock();
  await redis.flushall();
});

describe("rate limiter", () => {
  it("allows up to max then blocks with seconds to wait", async () => {
    const rl = createRateLimiter(redis);
    expect((await rl.check("login", "1.1.1.1", opts)).allowed).toBe(true);
    expect((await rl.check("login", "1.1.1.1", opts)).allowed).toBe(true);
    const third = await rl.check("login", "1.1.1.1", opts);
    expect(third.allowed).toBe(false);
    expect(third.retryAfterSeconds).toBeGreaterThanOrEqual(1);
    expect(third.retryAfterSeconds).toBeLessThanOrEqual(60);
  });

  it("recovers after the window passes", async () => {
    const rl = createRateLimiter(redis);
    for (let i = 0; i < 3; i++) await rl.check("login", "1.1.1.1", opts);
    await redis.flushall(); // 窗口过期等价于 key 消失
    expect((await rl.check("login", "1.1.1.1", opts)).allowed).toBe(true);
  });

  it("counts per IP and per route group independently", async () => {
    const rl = createRateLimiter(redis);
    for (let i = 0; i < 3; i++) await rl.check("login", "1.1.1.1", opts);
    expect((await rl.check("login", "2.2.2.2", opts)).allowed).toBe(true);
    expect((await rl.check("other", "1.1.1.1", opts)).allowed).toBe(true);
  });

  it("repairs a key that lost its expiry", async () => {
    const rl = createRateLimiter(redis);
    await redis.set("rl:login:1.1.1.1", "5"); // 无 TTL
    const r = await rl.check("login", "1.1.1.1", opts);
    expect(r.allowed).toBe(false);
    expect(await redis.ttl("rl:login:1.1.1.1")).toBeGreaterThan(0);
  });
});

describe("client ip", () => {
  const h = (xff?: string) => new Headers(xff ? { "x-forwarded-for": xff } : {});

  it("ignores X-Forwarded-For when no proxy is trusted (hops=0)", () => {
    expect(getClientIp(h("6.6.6.6"), { hops: 0, connectionAddress: "10.0.0.1" })).toBe("10.0.0.1");
  });

  it("falls back to 'unknown' when the connection address is not available", () => {
    expect(getClientIp(h("6.6.6.6"), { hops: 0 })).toBe("unknown");
  });

  it("takes the entry added by the Nth trusted proxy counting from the right", () => {
    expect(getClientIp(h("6.6.6.6, 9.9.9.9"), { hops: 1 })).toBe("9.9.9.9");
    expect(getClientIp(h("6.6.6.6, 9.9.9.9, 10.1.1.1"), { hops: 2 })).toBe("9.9.9.9");
  });

  it("falls back to the connection address when the chain is shorter than hops", () => {
    expect(getClientIp(h("9.9.9.9"), { hops: 2, connectionAddress: "10.0.0.1" })).toBe("10.0.0.1");
  });
});
