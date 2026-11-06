import type { PageQuery } from './types';

export const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 100;

type SearchParams = Record<string, string | string[] | undefined>;

function firstValue(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/** `page` is zero-based, as in tenant-core-api; invalid values fall back to the defaults. */
export function parsePageQuery(params: SearchParams): PageQuery {
  const page = Number(firstValue(params.page));
  const size = Number(firstValue(params.size));
  return {
    page: firstValue(params.page) && Number.isInteger(page) && page >= 0 ? page : 0,
    size:
      firstValue(params.size) && Number.isInteger(size) && size >= 1 && size <= MAX_PAGE_SIZE
        ? size
        : DEFAULT_PAGE_SIZE,
  };
}
