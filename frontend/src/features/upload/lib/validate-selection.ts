import { z } from 'zod';

type SelectionRejection = 'EMPTY_FILE' | 'FILE_TOO_LARGE';

/**
 * Client-side admission check (rule F-8). The server remains the authority
 * and enforces the same limit; this check avoids sending up to 500 MB only to
 * receive a 413 — which several browsers report as a network error when the
 * server answers before the end of the upload.
 */
export function createFileSelectionSchema(maxBytes: number) {
  return z
    .file()
    .min(1, { error: 'EMPTY_FILE' satisfies SelectionRejection })
    .max(maxBytes, { error: 'FILE_TOO_LARGE' satisfies SelectionRejection });
}

export function validateSelection(
  schema: ReturnType<typeof createFileSelectionSchema>,
  file: File,
): SelectionRejection | null {
  const result = schema.safeParse(file);
  if (result.success) return null;
  return result.error.issues[0]?.message === 'EMPTY_FILE' ? 'EMPTY_FILE' : 'FILE_TOO_LARGE';
}
