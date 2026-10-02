import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { ApiError } from '@/api/api-error';
import { fileQueries } from '@/api/files/files-queries';
import type { FileStatus } from '@/api/files/files-schemas';
import type { ErrorCode } from '@/api/problem-schema';
import { describeError } from '@/i18n/messages';
import { startBrowserDownload } from '@/lib/browser-download';

interface DownloadRequest {
  fileId: string;
  filename: string;
}

/** What the server would answer for a file in this status: the same message, one request earlier. */
const REFUSALS: Record<FileStatus, ErrorCode> = {
  PENDING: 'FILE_NOT_READY',
  SCANNING: 'FILE_NOT_READY',
  AVAILABLE: 'UNKNOWN',
  INFECTED: 'FILE_INFECTED',
  UNSCANNABLE: 'FILE_UNSCANNABLE',
  FAILED: 'FILE_SCAN_FAILED',
  UNKNOWN: 'UNKNOWN',
};

/**
 * Reads the file again at click time, then lets the browser download it
 * natively from the content URL the server gives (rules F-4, F-5). The
 * session cookie goes along by itself: no link to request, no second
 * credential (ADR-0013).
 *
 * Reading first is for the user, not for security — the server checks again
 * when it serves. A refusal, or an expired session, is explained here instead
 * of ending as a failed download in the browser's download bar.
 */
export function useDownloadFile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ fileId }: DownloadRequest) => {
      const file = await queryClient.query({ ...fileQueries.detail(fileId), staleTime: 0 });
      if (!file.downloadable || !file.links.content) {
        throw new ApiError({ kind: 'http', status: 409, code: REFUSALS[file.status] });
      }
      return file.links.content;
    },
    onSuccess: (url, { filename }) => startBrowserDownload(url, filename),
    onError: (error, { fileId }) => {
      toast.error(describeError(error));
      // The file probably changed status: show the server's current view.
      void queryClient.invalidateQueries({ queryKey: fileQueries.detail(fileId).queryKey });
      void queryClient.invalidateQueries({ queryKey: fileQueries.lists() });
    },
  });
}
