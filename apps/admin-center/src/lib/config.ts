import "server-only";
import { z } from "zod";

/**
 * 环境变量配置（只在服务端）。
 *
 * 刻意**不在模块加载时解析**：`next build` 的构建环境没有运行期变量，加载即解析会让构建失败。
 * 缺失变量的快速失败发生在服务启动时（`src/instrumentation.ts` 的 `register()` 调用 `getConfig()`）。
 */
const schema = z.object({
  UAM_BASE_URL: z.url(),
  UAM_SERVICE_KEY: z.string().min(1),
  UAM_TIMEOUT_MS: z.coerce.number().int().positive().default(1500),
  SESSION_COOKIE_NAME: z.string().min(1).default("__Host-asid"),
  TRUSTED_PROXY_HOPS: z.coerce.number().int().min(0).default(1),
});

export interface AppConfig {
  uamBaseUrl: string;
  uamServiceKey: string;
  uamTimeoutMs: number;
  sessionCookieName: string;
  trustedProxyHops: number;
}

export class ConfigError extends Error {
  constructor(variables: string[]) {
    // 只报变量名，绝不回显值（其中有服务密钥）
    super(`环境变量缺失或不合法：${variables.join("、")}`);
    this.name = "ConfigError";
  }
}

export function parseConfig(env: Record<string, string | undefined>): AppConfig {
  // 空串视同未设置（.env.example 里的占位写法）
  const cleaned = Object.fromEntries(Object.entries(env).filter(([, v]) => v !== undefined && v !== ""));
  const result = schema.safeParse(cleaned);
  if (!result.success) {
    const names = [...new Set(result.error.issues.map((i) => String(i.path[0])))];
    throw new ConfigError(names);
  }
  const v = result.data;
  return {
    uamBaseUrl: v.UAM_BASE_URL.replace(/\/+$/, ""),
    uamServiceKey: v.UAM_SERVICE_KEY,
    uamTimeoutMs: v.UAM_TIMEOUT_MS,
    sessionCookieName: v.SESSION_COOKIE_NAME,
    trustedProxyHops: v.TRUSTED_PROXY_HOPS,
  };
}

/** 读取并校验当前进程的配置；每次调用都解析（五个变量，开销可忽略），不缓存。 */
export function getConfig(): AppConfig {
  return parseConfig(process.env);
}
