import { NextResponse, type NextRequest } from "next/server";

/**
 * 只做两件事：给每个请求生成 CSP nonce，并设置安全响应头。**不发网络请求**（会话校验在服务端渲染层做）。
 * nonce 与 CSP 同时写进请求头，Next 渲染时据此给自己的脚本加 nonce。
 * 样式放行 `'unsafe-inline'`：Radix / shadcn 组件依赖内联样式属性。
 */
export function middleware(request: NextRequest) {
  const nonce = btoa(crypto.randomUUID());
  const dev = process.env.NODE_ENV === "development";
  const csp = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${dev ? " 'unsafe-eval'" : ""}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join("; ");

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("content-security-policy", csp);

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set("Content-Security-Policy", csp);
  response.headers.set("X-Content-Type-Options", "nosniff");
  response.headers.set("Referrer-Policy", "same-origin");
  return response;
}

export const config = {
  // 静态资源与图标不需要
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
