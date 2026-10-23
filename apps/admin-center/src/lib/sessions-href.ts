/** 在线用户列表地址：带页码（第 1 页不写 `page`）。 */
export function sessionsHref(page: number): string {
  return page > 1 ? `/sessions?page=${page}` : "/sessions";
}
