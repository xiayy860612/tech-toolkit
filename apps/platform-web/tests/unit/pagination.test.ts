import { describe, expect, it } from 'vitest';
import { formatDateTime } from '@/lib/format';
import { DEFAULT_PAGE_SIZE, parsePageQuery } from '@/lib/pagination';

describe('parsePageQuery', () => {
  it('defaults to the first page', () => {
    expect(parsePageQuery({})).toEqual({ page: 0, size: DEFAULT_PAGE_SIZE });
  });

  it('reads page and size', () => {
    expect(parsePageQuery({ page: '2', size: '50' })).toEqual({ page: 2, size: 50 });
  });

  it('uses the first value of a repeated parameter', () => {
    expect(parsePageQuery({ page: ['4', '9'] })).toEqual({ page: 4, size: DEFAULT_PAGE_SIZE });
  });

  it.each(['-1', 'abc', '1.5', ''])('falls back to page 0 for page=%s', (page) => {
    expect(parsePageQuery({ page }).page).toBe(0);
  });

  it.each(['0', '101', 'abc', '-5'])('falls back to the default size for size=%s', (size) => {
    expect(parsePageQuery({ size }).size).toBe(DEFAULT_PAGE_SIZE);
  });

  it('accepts the size boundaries', () => {
    expect(parsePageQuery({ size: '1' }).size).toBe(1);
    expect(parsePageQuery({ size: '100' }).size).toBe(100);
  });
});

describe('formatDateTime', () => {
  it('formats an ISO timestamp in UTC', () => {
    expect(formatDateTime('2030-01-02T03:04:05Z')).toBe('2030-01-02 03:04 UTC');
  });

  it('returns the input when it is not a date', () => {
    expect(formatDateTime('nope')).toBe('nope');
  });
});
