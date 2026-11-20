#!/usr/bin/env node
// 生成开发用 Ed25519 密钥对（JWK）到被 .gitignore 忽略的 .keys/。
// 私钥给 BFF（INTERNAL_JWT_PRIVATE_KEY_PATH），公钥给后端（INTERNAL_JWT_PUBLIC_KEY，JWK 文件路径或 JSON）。
// 绝不把任何密钥提交入库。
import { generateKeyPairSync, randomUUID } from "node:crypto";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const keysDir = join(dirname(fileURLToPath(import.meta.url)), "..", ".keys");
const privatePath = join(keysDir, "internal-jwt-private.jwk.json");
const publicPath = join(keysDir, "internal-jwt-public.jwk.json");

if (existsSync(privatePath) && !process.argv.includes("--force")) {
  console.log(`已存在 ${privatePath}，如需重新生成请加 --force`);
  process.exit(0);
}

const { publicKey, privateKey } = generateKeyPairSync("ed25519");
const kid = `dev-${randomUUID().slice(0, 8)}`;
const base = { alg: "EdDSA", use: "sig", kid };
const priv = { ...privateKey.export({ format: "jwk" }), ...base };
const pub = { ...publicKey.export({ format: "jwk" }), ...base };

mkdirSync(keysDir, { recursive: true });
writeFileSync(privatePath, JSON.stringify(priv), { mode: 0o600 });
writeFileSync(publicPath, JSON.stringify(pub));
console.log(`私钥（BFF）: ${privatePath}`);
console.log(`公钥（后端 INTERNAL_JWT_PUBLIC_KEY）: ${publicPath}`);
