import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { importJWK, SignJWT, type JWK } from "jose";
import { getConfig } from "../config";

/** 会话身份（来自后端 resolve），用于签发 typ=user JWT。 */
export interface UserIdentity {
  sessionId: string;
  userId: string;
  accountId: string;
  tenantId: number;
}

type SigningKey = { key: Awaited<ReturnType<typeof importJWK>>; kid?: string };
const keyCache = new Map<string, SigningKey>();

async function signingKey(path: string): Promise<SigningKey> {
  const cached = keyCache.get(path);
  if (cached) return cached;
  const jwk = JSON.parse(readFileSync(path, "utf8")) as JWK;
  const loaded = { key: await importJWK(jwk, "EdDSA"), kid: jwk.kid };
  keyCache.set(path, loaded);
  return loaded;
}

async function sign(claims: Record<string, unknown>): Promise<string> {
  const cfg = getConfig();
  const { key, kid } = await signingKey(cfg.jwtPrivateKeyPath);
  return new SignJWT(claims)
    .setProtectedHeader({ alg: "EdDSA", ...(kid ? { kid } : {}) })
    .setIssuer(cfg.jwtIssuer)
    .setAudience(cfg.jwtAudience)
    .setJti(randomUUID())
    .setIssuedAt()
    .setExpirationTime(`${cfg.jwtTtlSeconds}s`)
    .sign(key);
}

/** typ=user：租户只取自 resolve 返回的会话身份；不含 role。 */
export function signUserJwt(identity: UserIdentity, cip: string): Promise<string> {
  return sign({
    typ: "user",
    sub: identity.userId,
    tenant_id: identity.tenantId,
    account_id: identity.accountId,
    sid: identity.sessionId,
    cip,
  });
}

/** typ=service：仅用于登录与会话解析，租户取自 DEFAULT_TENANT_ID，不含用户声明。 */
export function signServiceJwt(cip: string): Promise<string> {
  return sign({ typ: "service", tenant_id: getConfig().defaultTenantId, cip });
}
