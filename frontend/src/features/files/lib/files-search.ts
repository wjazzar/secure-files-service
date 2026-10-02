import { z } from 'zod';

import { DEFAULT_SORT, FILE_STATUSES, type FilesListParams, SORT_OPTIONS } from '@/api/files/files-schemas';

export const PAGE_SIZES = [10, 20, 50] as const;
const DEFAULT_PAGE_SIZE = 20;

/**
 * The contract serves a page only if it starts at most 10 000 files deep
 * (`page × size ≤ 10 000`, page counted from zero); deeper, it answers `400`.
 * Past that, a search or a status filter narrows the list instead.
 */
const MAX_DEPTH = 10_000;

/** The last page, counted from one, that the API serves at this page size. */
export function lastReachablePage(size: number): number {
  return Math.floor(MAX_DEPTH / size) + 1;
}

/**
 * State of the file table, held in the URL: a link shared or reloaded shows
 * the same view.
 *
 * Every field `.catch`es its default: a hand-edited or outdated URL is
 * corrected instead of breaking the screen. `page` is one-based for humans;
 * the API is zero-based, and stops at {@link lastReachablePage}.
 */
const FilesSearchFields = z.object({
  page: z.coerce.number().int().min(1).catch(1),
  size: z.coerce
    .number()
    .pipe(z.union(PAGE_SIZES.map((size) => z.literal(size))))
    .catch(DEFAULT_PAGE_SIZE),
  sort: z.enum(SORT_OPTIONS).catch(DEFAULT_SORT),
  // Filter values: known statuses only. One invalid value drops the filter.
  status: z.array(z.enum(FILE_STATUSES)).catch([]),
  q: z.string().trim().min(1).max(100).optional().catch(undefined),
});

export const FilesSearchSchema = FilesSearchFields
  // A page too deep for the API (hand-edited link) is brought back within reach.
  .transform((search) => ({ ...search, page: Math.min(search.page, lastReachablePage(search.size)) }));
export type FilesSearch = z.infer<typeof FilesSearchSchema>;

export function parseFilesSearch(params: URLSearchParams): FilesSearch {
  return FilesSearchSchema.parse({
    page: params.get('page') ?? undefined,
    size: params.get('size') ?? undefined,
    sort: params.get('sort') ?? undefined,
    status: params.getAll('status'),
    q: params.get('q') ?? undefined,
  });
}

/** Default values are left out, so that the URL stays short. */
export function serializeFilesSearch(search: FilesSearch): URLSearchParams {
  const params = new URLSearchParams();
  if (search.q) params.set('q', search.q);
  search.status.forEach((status) => params.append('status', status));
  if (search.sort !== DEFAULT_SORT) params.set('sort', search.sort);
  if (search.size !== DEFAULT_PAGE_SIZE) params.set('size', String(search.size));
  if (search.page > 1) params.set('page', String(search.page));
  return params;
}

/** Any number → an accepted page size (the default when not accepted). */
export function toPageSize(value: number): FilesSearch['size'] {
  return FilesSearchFields.shape.size.parse(value);
}

export function toListParams(search: FilesSearch): FilesListParams {
  return { page: search.page - 1, size: search.size, sort: search.sort, status: search.status, q: search.q };
}
