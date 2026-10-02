import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';

import { type FilesSearch, parseFilesSearch, serializeFilesSearch } from '../lib/files-search';

type FilesSearchPatch = Partial<Omit<FilesSearch, 'page'>> | { page: number };

/**
 * Table state read from and written to the URL, validated by Zod.
 *
 * Changing the search, a filter, the sort or the page size goes back to the
 * first page: the current page number would not mean anything any more.
 */
export function useFilesSearchParams(): [
  FilesSearch,
  (patch: FilesSearchPatch, options?: { replace?: boolean }) => void,
] {
  const [searchParams, setSearchParams] = useSearchParams();
  const search = useMemo(() => parseFilesSearch(searchParams), [searchParams]);

  const update = useCallback(
    (patch: FilesSearchPatch, options?: { replace?: boolean }) => {
      setSearchParams(
        (current) => {
          const previous = parseFilesSearch(current);
          const next: FilesSearch = 'page' in patch ? { ...previous, ...patch } : { ...previous, ...patch, page: 1 };
          return serializeFilesSearch(next);
        },
        // Typing in the search box must not fill the history with one entry per keystroke.
        { replace: options?.replace ?? 'q' in patch },
      );
    },
    [setSearchParams],
  );

  return [search, update];
}
