import { z } from 'zod';

import { isContentLink } from '@/lib/safe-url';

import { tolerantEnum } from '../tolerant-enum';

/**
 * Zod schemas mirroring `contracts/openapi.yaml`, the single source of truth.
 *
 * TypeScript types are derived from these schemas (`z.infer`), never written
 * next to them. `files-schemas.contract.test.ts` fails when the contract and
 * these schemas drift apart.
 *
 * Enumerations are parsed with `tolerantEnum` (rule F-2).
 */

export const FILE_STATUSES = ['PENDING', 'SCANNING', 'AVAILABLE', 'INFECTED', 'UNSCANNABLE', 'FAILED'] as const;
export type KnownFileStatus = (typeof FILE_STATUSES)[number];
export const FileStatusSchema = tolerantEnum(FILE_STATUSES);
export type FileStatus = z.infer<typeof FileStatusSchema>;

export const STATUS_REASON_CODES = [
  'SCAN_RETRY_SCHEDULED',
  'EXCEEDS_SCANNER_SIZE_LIMIT',
  'ENCRYPTED_ARCHIVE',
  'SCANNER_LIMITS_EXCEEDED',
  'SCAN_ATTEMPTS_EXHAUSTED',
] as const;
export const StatusReasonCodeSchema = tolerantEnum(STATUS_REASON_CODES);
export type StatusReasonCode = z.infer<typeof StatusReasonCodeSchema>;

export const SCAN_RESULTS = ['CLEAN', 'INFECTED', 'UNSCANNABLE'] as const;
const ScanResultSchema = tolerantEnum(SCAN_RESULTS);

/** Sort options accepted by `GET /files` (`field,direction`). */
export const SORT_OPTIONS = [
  'uploadedAt,desc',
  'uploadedAt,asc',
  'filename,asc',
  'filename,desc',
  'sizeBytes,desc',
  'sizeBytes,asc',
] as const;
export type SortOption = (typeof SORT_OPTIONS)[number];
export const DEFAULT_SORT: SortOption = 'uploadedAt,desc';

const Timestamp = z.iso.datetime({ offset: true });

export const FileSummarySchema = z.object({
  id: z.uuid(),
  filename: z.string(),
  sizeBytes: z.number().int().nonnegative(),
  contentType: z.string(),
  status: FileStatusSchema,
  downloadable: z.boolean(),
  terminal: z.boolean(),
  uploadedAt: Timestamp,
  statusChangedAt: Timestamp,
});
export type FileSummary = z.infer<typeof FileSummarySchema>;

export const ScanVerdictSchema = z.object({
  result: ScanResultSchema,
  threatName: z.string().nullish(),
  engine: z.string(),
  engineVersion: z.string().nullish(),
  signatureVersion: z.string().nullish(),
  scannedAt: Timestamp,
  durationMs: z.number().int().nonnegative(),
});

export const FileLinksSchema = z.object({
  self: z.string(),
  /** Followed by a native navigation: only what the contract promises (audit S-14). */
  content: z.string().refine(isContentLink, 'Unexpected content link').nullish(),
});

export const FileDetailSchema = FileSummarySchema.extend({
  sha256: z.string(),
  scanAttempts: z.number().int().nonnegative(),
  scan: ScanVerdictSchema.nullable(),
  statusReason: StatusReasonCodeSchema.nullable(),
  links: FileLinksSchema,
});
export type FileDetail = z.infer<typeof FileDetailSchema>;

export const PageMetadataSchema = z.object({
  size: z.number().int().positive(),
  number: z.number().int().nonnegative(),
  totalElements: z.number().int().nonnegative(),
  totalPages: z.number().int().nonnegative(),
});

export const FilePageSchema = z.object({
  content: z.array(FileSummarySchema),
  page: PageMetadataSchema,
});
export type FilePage = z.infer<typeof FilePageSchema>;

export const FilesSummarySchema = z.object({
  total: z.number().int().nonnegative(),
  byStatus: z.record(z.string(), z.number().int().nonnegative()),
});
export type FilesSummary = z.infer<typeof FilesSummarySchema>;

/** Query parameters of `GET /files`. `page` is zero-based, as in the API. */
export interface FilesListParams {
  page: number;
  size: number;
  sort: SortOption;
  status: readonly KnownFileStatus[];
  q?: string | undefined;
}
