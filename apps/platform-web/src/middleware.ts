import { NextResponse, type NextRequest } from 'next/server';

// Edge runtime: no Redis here. Only cookie presence is checked; the real Session
// validation lives in src/server/auth.ts (requireSession) and runs on the Node server.
const SESSION_COOKIE = 'platform_sid';
const PUBLIC_PATHS = new Set(['/login', '/session/expired', '/healthz']);

export function middleware(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const hasSession = Boolean(request.cookies.get(SESSION_COOKIE)?.value);

  if (PUBLIC_PATHS.has(pathname)) {
    if (pathname === '/login' && hasSession) {
      return NextResponse.redirect(new URL('/tenants', request.url));
    }
    return NextResponse.next();
  }

  const requested = `${pathname}${search}`;
  if (!hasSession) {
    const loginUrl = new URL('/login', request.url);
    loginUrl.searchParams.set('next', requested);
    return NextResponse.redirect(loginUrl);
  }

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set('x-next-path', requested);
  return NextResponse.next({ request: { headers: requestHeaders } });
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico).*)'],
};
