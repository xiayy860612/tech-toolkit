/** 用户列表地址：带搜索词与页码（第 1 页不写 `page`）。 */
export function usersHref(query: string, page: number): string {
  const params: string[] = [];
  if (query) params.push(`q=${encodeURIComponent(query)}`);
  if (page > 1) params.push(`page=${page}`);
  return params.length ? `/users?${params.join("&")}` : "/users";
}
