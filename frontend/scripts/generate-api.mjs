#!/usr/bin/env node
// 由契约生成前端类型：后端契约 -> schema.d.ts，BFF 自有接口 -> bff-schema.d.ts（生成文件，提交入库）。
import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const contracts = join(root, "..", "specs", "001-tenant-admin-center", "contracts");

for (const [input, output] of [
  ["openapi.yaml", "schema.d.ts"],
  ["bff-openapi.yaml", "bff-schema.d.ts"],
]) {
  execFileSync(
    "pnpm",
    [
      "exec",
      "openapi-typescript",
      join(contracts, input),
      "-o",
      join(root, "src", "lib", "api", output),
    ],
    { stdio: "inherit", cwd: root },
  );
}
