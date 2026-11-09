import { NextResponse, type NextRequest } from 'next/server';
import { safeNextPath } from '@/lib/safeNextPath';
import { getSessionStore } from '@/server/container';
import { SESSION_COOKIE } from '@/server/sessionCookie';

// Landing point when the Session is gone or the backend rejected the Opaque Token (401).
// Server components cannot delete cookies, so this handler clears it and returns to /login.
export async function GET(request: NextRequest) {
  const sessionId = request.cookies.get(SESSION_COOKIE)?.value;
  if (sessionId) await getSessionStore().destroy(sessionId);

  const next = request.nextUrl.searchParams.get('next');
  const safe = safeNextPath(next, '');
  const location = safe ? `/login?next=${encodeURIComponent(safe)}` : '/login';

  const response = new NextResponse(null, { status: 303, headers: { Location: location } });
  response.cookies.delete(SESSION_COOKIE);
  return response;
}
