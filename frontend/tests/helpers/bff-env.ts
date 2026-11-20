import { exportJWK, generateKeyPair, jwtVerify } from "jose";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

export const TEST_ENV_DEFAULTS: Record<string, string> = {
  BACKEND_URL: "http://backend.test",
  REDIS_URL: "redis://localhost:6379",
  DEFAULT_TENANT_ID: "0",
  SESSION_COOKIE_NAME: "SESSION_ID",
  SESSION_RESOLVE_CACHE_TTL_SECONDS: "5",
  INTERNAL_JWT_ISSUER: "admin-center-bff",
  INTERNAL_JWT_AUDIENCE: "tenant-core-api",
  INTERNAL_JWT_TTL_SECONDS: "60",
  TRUSTED_PROXY_HOPS: "0",
  LOGIN_RATE_LIMIT_MAX: "3",
  LOGIN_RATE_LIMIT_WINDOW_SECONDS: "60",
  COOKIE_SECURE: "true",
  BACKEND_TIMEOUT_MS: "2000",
};

/** 生成测试用 Ed25519 密钥并写入 BFF 环境变量；返回按公钥校验 JWT 的函数。 */
export async function setupBffEnv(overrides: Record<string, string> = {}) {
  const { publicKey, privateKey } = await generateKeyPair("EdDSA", {
    crv: "Ed25519",
    extractable: true,
  });
  const dir = mkdtempSync(join(tmpdir(), "bff-keys-"));
  const keyPath = join(dir, "private.jwk.json");
  writeFileSync(
    keyPath,
    JSON.stringify({ ...(await exportJWK(privateKey)), kid: "test-kid", alg: "EdDSA" }),
  );
  Object.assign(
    process.env,
    TEST_ENV_DEFAULTS,
    { INTERNAL_JWT_PRIVATE_KEY_PATH: keyPath },
    overrides,
  );
  return {
    async verify(token: string) {
      return jwtVerify(token, publicKey, {
        issuer: "admin-center-bff",
        audience: "tenant-core-api",
        algorithms: ["EdDSA"],
      });
    },
  };
}

export const IDENTITY = {
  sessionId: "11111111-1111-4111-8111-111111111111",
  userId: "22222222-2222-4222-8222-222222222222",
  accountId: "33333333-3333-4333-8333-333333333333",
  tenantId: 0,
  displayName: "管理员",
  mustChangePassword: false,
  expiresAt: "2099-01-01T00:00:00Z",
};

export function problem(status: number, code: string): Response {
  return new Response(JSON.stringify({ type: "about:blank", title: code, status, code }), {
    status,
    headers: { "content-type": "application/problem+json" },
  });
}

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}
