import Redis from "ioredis";
import { getConfig } from "./config";

let client: Redis | undefined;

/**
 * Redis 仅用于 BFF 限流计数。首条命令触发连接（命令在连接期间排队），Redis 不可达时
 * 命令在 1 次重试或 500ms 内失败，由调用方决定放行（登录限流为放行并记录）。
 * 注意不能同时用 lazyConnect 与 enableOfflineQueue:false——那样首条命令总会失败。
 */
export function getRedis(): Redis {
  client ??= new Redis(getConfig().redisUrl, {
    lazyConnect: true,
    maxRetriesPerRequest: 1,
    commandTimeout: 500,
    connectTimeout: 1000,
  });
  return client;
}
