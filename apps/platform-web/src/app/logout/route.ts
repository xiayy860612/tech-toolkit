import { NextResponse, type NextRequest } from 'next/server';
import { getSessionStore, getTenantCoreClient } from '@/server/container';
import { SESSION_COOKIE } from '@/server/sessionCookie';

// POST only: logging out through a GET link would be CSRF-able.
export async function POST(request: NextRequest) {
  const sessionId = request.cookies.get(SESSION_COOKIE)?.value;
  const store = getSessionStore();

  if (sessionId) {
    const session = await store.get(sessionId);
    if (session) {
      try {
        await getTenantCoreClient().logout(session.token);
      } catch {
        // Revocation is best effort here: the local Session is destroyed regardless.
      }
    }
    await store.destroy(sessionId);
  }

  const response = new NextResponse(null, { status: 303, headers: { Location: '/login' } });
  response.cookies.delete(SESSION_COOKIE);
  return response;
}
