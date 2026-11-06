/** Accepts only same-origin relative paths (never /login) to avoid open redirects and loops. */
export function safeNextPath(next: string | null | undefined, fallback = '/tenants'): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) {
    return fallback;
  }
  if (next === '/login' || next.startsWith('/login?')) return fallback;
  return next;
}
