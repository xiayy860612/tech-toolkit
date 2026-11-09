import { describe, expect, it } from 'vitest';
import { safeNextPath } from '@/lib/safeNextPath';

describe('safeNextPath', () => {
  it('keeps same-origin relative paths with query', () => {
    expect(safeNextPath('/users?page=2')).toBe('/users?page=2');
  });

  it.each([
    undefined,
    null,
    '',
    'users',
    '//evil.example',
    '/\\evil.example',
    'https://evil.example',
    '/login',
    '/login?next=/users',
  ])('falls back to /tenants for %s', (value) => {
    expect(safeNextPath(value)).toBe('/tenants');
  });

  it('uses a custom fallback', () => {
    expect(safeNextPath('//x', '/modules')).toBe('/modules');
  });
});
