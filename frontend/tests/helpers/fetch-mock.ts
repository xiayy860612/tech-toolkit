import { vi } from "vitest";

export interface RecordedCall {
  method: string;
  path: string;
  query: URLSearchParams;
  body: unknown;
}

export type Handler = (call: RecordedCall) => Response | Promise<Response>;

export const jsonRes = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

export const noContent = () => new Response(null, { status: 204 });

export const problemRes = (status: number, code: string, errors?: unknown[]) =>
  new Response(JSON.stringify({ type: "about:blank", title: code, status, code, errors }), {
    status,
    headers: { "content-type": "application/problem+json" },
  });

/**
 * 以 "METHOD /path"（不含 /api/v1 前缀与查询串）为键桩住全局 fetch，并记录所有调用。
 * 未登记的请求返回 500，使遗漏的接口在测试里立刻暴露。
 */
export function mockApi(routes: Record<string, Handler>) {
  const calls: RecordedCall[] = [];
  const fetchMock = vi.fn(async (input: string, init: RequestInit = {}) => {
    const url = new URL(input, "http://app.test");
    const call: RecordedCall = {
      method: (init.method ?? "GET").toUpperCase(),
      path: url.pathname.replace(/^\/api\/v1/, ""),
      query: url.searchParams,
      body: typeof init.body === "string" ? JSON.parse(init.body) : undefined,
    };
    calls.push(call);
    const handler = routes[`${call.method} ${call.path}`];
    return handler ? handler(call) : problemRes(500, `UNMOCKED ${call.method} ${call.path}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  return {
    calls,
    fetchMock,
    callsTo: (key: string) => calls.filter((c) => `${c.method} ${c.path}` === key),
  };
}
