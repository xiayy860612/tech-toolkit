import Redis from "ioredis";
import { getConfig } from "./config";

let client: Redis | undefined;

/** Redis 仅用于 BFF 限流计数；懒连接，失败快速返回而不是排队。 */
export function getRedis(): Redis {
  client ??= new Redis(getConfig().redisUrl, {
    lazyConnect: true,
    maxRetriesPerRequest: 1,
    enableOfflineQueue: false,
  });
  return client;
}
