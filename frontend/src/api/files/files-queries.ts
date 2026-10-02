import { keepPreviousData, queryOptions } from '@tanstack/react-query';

import { getFile, getFiles, getFilesSummary } from './files-api';
import type { FileSummary, FilesListParams } from './files-schemas';

const MIN_POLL_MS = 2_000;
const MAX_POLL_MS = 10_000;

/**
 * Polling delay for the files still being analysed, or `false` when they are
 * all terminal (polling stops).
 *
 * Fresh activity is followed closely (2 s after a status change), a file that
 * has been waiting for a while less often (up to 10 s). Polling also pauses
 * on its own while the tab is hidden (TanStack Query default).
 */
export function pollDelay(
  files: readonly Pick<FileSummary, 'terminal' | 'statusChangedAt'>[],
  now = Date.now(),
): number | false {
  const active = files.filter((file) => !file.terminal);
  if (active.length === 0) return false;
  const lastChange = Math.max(...active.map((file) => Date.parse(file.statusChangedAt)));
  const waitingMs = Math.max(0, now - lastChange);
  return Math.min(MIN_POLL_MS + waitingMs / 5, MAX_POLL_MS);
}

/**
 * Query factory: keys and fetchers live together, so a key always declares
 * the dependencies of its fetcher. Components call
 * `useQuery(fileQueries.detail(id))`; mutations invalidate `fileQueries.all()`.
 */
export const fileQueries = {
  all: () => ['files'] as const,
  lists: () => [...fileQueries.all(), 'list'] as const,
  list: (params: FilesListParams) =>
    queryOptions({
      queryKey: [...fileQueries.lists(), params] as const,
      queryFn: ({ signal }) => getFiles(params, { signal }),
      // The previous page stays on screen while the next one loads.
      placeholderData: keepPreviousData,
      // One request per interval refreshes every visible file still being analysed.
      refetchInterval: (query) => pollDelay(query.state.data?.content ?? []),
    }),
  summary: (q: string | undefined) =>
    queryOptions({
      queryKey: [...fileQueries.all(), 'summary', q ?? ''] as const,
      queryFn: ({ signal }) => getFilesSummary(q, { signal }),
      placeholderData: keepPreviousData,
      refetchInterval: (query) => {
        const byStatus = query.state.data?.byStatus;
        const inProgress = (byStatus?.PENDING ?? 0) + (byStatus?.SCANNING ?? 0);
        return inProgress > 0 ? MIN_POLL_MS * 2 : false;
      },
    }),
  detail: (fileId: string) =>
    queryOptions({
      queryKey: [...fileQueries.all(), 'detail', fileId] as const,
      queryFn: ({ signal }) => getFile(fileId, { signal }),
      refetchInterval: (query) => pollDelay(query.state.data ? [query.state.data] : []),
    }),
};
