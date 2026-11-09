// @vitest-environment node
import { NextRequest } from 'next/server';
import { describe, expect, it } from 'vitest';
import { config, middleware } from '@/middleware';

function call(path: string, cookie?: string) {
  const headers = new Headers();
  if (cookie) headers.set('cookie', `platform_sid=${cookie}`);
  return middleware(new NextRequest(`http://localhost:3000${path}`, { headers }));
}

function location(response: Response): URL {
  return new URL(response.headers.get('location')!);
}

describe('middleware', () => {
  it.each(['/tenants', '/modules', '/users', '/'])(
    'redirects %s to /login?next=<path> without a session cookie',
    (path) => {
      const response = call(path);
      expect(response.status).toBe(307);
      const target = location(response);
      expect(target.pathname).toBe('/login');
      expect(target.searchParams.get('next')).toBe(path);
    },
  );

  it('keeps the query string in next', () => {
    const target = location(call('/users?page=2&size=50'));
    expect(target.searchParams.get('next')).toBe('/users?page=2&size=50');
  });

  it('treats an empty cookie value as not logged in', () => {
    const response = call('/tenants', '');
    expect(location(response).pathname).toBe('/login');
  });

  it('lets a request with a session cookie through and exposes the requested path', () => {
    const response = call('/modules?page=1', 'sid123');
    expect(response.headers.get('location')).toBeNull();
    expect(response.headers.get('x-middleware-request-x-next-path')).toBe('/modules?page=1');
  });

  it('allows /login without a cookie', () => {
    const response = call('/login');
    expect(response.headers.get('location')).toBeNull();
  });

  it('redirects a logged-in user visiting /login to /tenants', () => {
    const response = call('/login', 'sid123');
    expect(location(response).pathname).toBe('/tenants');
  });

  it.each(['/session/expired', '/healthz'])('allows %s without a cookie', (path) => {
    expect(call(path).headers.get('location')).toBeNull();
  });

  it('excludes static assets from the matcher', () => {
    expect(config.matcher[0]).toContain('_next/static');
    expect(config.matcher[0]).toContain('favicon.ico');
  });
});
