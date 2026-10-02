import { z } from 'zod';

import { tolerantEnum } from './tolerant-enum';

export const ERROR_CODES = [
  'INVALID_PARAMETER',
  'INVALID_FILE_NAME',
  'EMPTY_FILE',
  'LENGTH_REQUIRED',
  'CONTENT_LENGTH_MISMATCH',
  'FILE_TOO_LARGE',
  'UPLOAD_TOO_SLOW',
  'IDEMPOTENCY_REQUEST_IN_PROGRESS',
  'IDEMPOTENCY_KEY_REUSED',
  'TOO_MANY_PENDING_FILES',
  'TOO_MANY_CONCURRENT_UPLOADS',
  'UNAUTHENTICATED',
  'CSRF_TOKEN_INVALID',
  'FILE_NOT_FOUND',
  'FILE_NOT_READY',
  'FILE_INFECTED',
  'FILE_UNSCANNABLE',
  'FILE_SCAN_FAILED',
  'RANGE_NOT_SATISFIABLE',
  'SERVICE_UNAVAILABLE',
  'INTERNAL_ERROR',
] as const;
export const ErrorCodeSchema = tolerantEnum(ERROR_CODES);
export type ErrorCode = z.infer<typeof ErrorCodeSchema>;

/**
 * RFC 9457 problem details, extended with a stable `code`. Read leniently:
 * only `code` matters to the interface, which branches on it and never on
 * `title` or `detail`.
 */
export const ProblemSchema = z.object({
  type: z.string().optional(),
  title: z.string().optional(),
  status: z.number().int().optional(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  code: ErrorCodeSchema,
  fileStatus: z.string().optional(),
  retryAfterSeconds: z.number().int().positive().optional(),
  maxFileSizeBytes: z.number().int().positive().optional(),
});
