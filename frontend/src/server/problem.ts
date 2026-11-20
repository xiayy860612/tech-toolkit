const TITLES: Record<number, string> = {
  400: "Bad Request",
  401: "Unauthorized",
  403: "Forbidden",
  404: "Not Found",
  429: "Too Many Requests",
  502: "Bad Gateway",
};

/** RFC 9457 problem+json，`code` 为稳定的机器可读码（见 bff-openapi.yaml 的 Problem）。 */
export function problemResponse(
  status: number,
  code: string,
  init: { detail?: string; headers?: HeadersInit | string[][] } = {},
): Response {
  const headers = new Headers(init.headers as HeadersInit);
  headers.set("content-type", "application/problem+json");
  headers.set("cache-control", "no-store");
  return new Response(
    JSON.stringify({
      type: "about:blank",
      title: TITLES[status] ?? "Error",
      status,
      code,
      ...(init.detail ? { detail: init.detail } : {}),
    }),
    { status, headers },
  );
}
