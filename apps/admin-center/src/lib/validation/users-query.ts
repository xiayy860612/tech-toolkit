import "server-only";
import { z } from "zod";

/** 与 UAM 契约（`GET /v1/users` 的 `query`）一致。 */
const MAX_QUERY_LENGTH = 64;

type Param = string | string[] | undefined;

const firstValue = (value: Param): string | undefined => (Array.isArray(value) ? value[0] : value);

const queryParam = z
  .string()
  .transform((s) => s.replace(/[\u0000-\u001f\u007f]/g, "").trim())
  .transform((s) => Array.from(s).slice(0, MAX_QUERY_LENGTH).join("").trim())
  .catch("");

// 只接受不带符号、不带小数的正整数（最多 7 位）；其余一律当作第 1 页
const pageParam = z
  .string()
  .regex(/^[1-9]\d{0,6}$/)
  .transform(Number)
  .catch(1);

/** `?page=`：缺失、非法一律是第 1 页；数组取第一项。用户与在线用户两个列表共用。 */
export function parsePage(raw: Param): number {
  return pageParam.parse(firstValue(raw) ?? "");
}

export interface UsersQuery {
  query: string;
  page: number;
}

/** 用户列表的查询参数（`?q=&page=`）：缺失、非法一律回到默认值，搜索词清掉控制字符并限长。 */
export function parseUsersQuery(search: { q?: Param; page?: Param }): UsersQuery {
  return {
    query: queryParam.parse(firstValue(search.q) ?? ""),
    page: parsePage(search.page),
  };
}

const userIdSchema = z.guid();

/** `userId` 路径参数：不是 UUID 格式则返回 null（按「用户不存在」处理，不请求 UAM）。 */
export function parseUserId(raw: string): string | null {
  const result = userIdSchema.safeParse(raw);
  return result.success ? result.data : null;
}

/** 会话引用（UAM 契约里是 UUID）：格式不对则返回 null，不请求 UAM。 */
export function parseSessionRef(raw: string): string | null {
  return parseUserId(raw);
}
