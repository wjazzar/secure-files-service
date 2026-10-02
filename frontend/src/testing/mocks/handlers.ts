import { delay, http, HttpResponse } from 'msw';

import {
  DEFAULT_SORT,
  FILE_STATUSES,
  type KnownFileStatus,
  SORT_OPTIONS,
  type SortOption,
} from '@/api/files/files-schemas';
import type { ErrorCode } from '@/api/problem-schema';

import { createMockDb, createMockFile, isVisibleTo, type MockDb, toDetail, toSummary } from './db';
import { createIdpHandlers } from './idp-handlers';

/** The contract's deepest page start: `page × size ≤ 10 000`. */
const MAX_DEPTH = 10_000;

/** Contains, ignoring case and accents — as the service's search (contract 1.9). */
function matches(filename: string, q: string): boolean {
  const fold = (value: string) => value.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  return fold(filename).includes(fold(q));
}

const API = '/api/v1';
const MAX_FILE_SIZE_BYTES = 500 * 1024 * 1024;

function problem(
  status: number,
  code: ErrorCode,
  extra: Record<string, unknown> = {},
  headers: Record<string, string> = {},
) {
  return HttpResponse.json(
    { type: 'about:blank', title: code, status, code, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  );
}

function compare(sort: SortOption) {
  const [field, direction] = sort.split(',') as ['uploadedAt' | 'filename' | 'sizeBytes', 'asc' | 'desc'];
  const factor = direction === 'asc' ? 1 : -1;
  return (a: ReturnType<typeof toSummary>, b: ReturnType<typeof toSummary>) => {
    const left = a[field];
    const right = b[field];
    const order =
      typeof left === 'number' && typeof right === 'number'
        ? left - right
        : String(left).localeCompare(String(right), 'fr');
    return (order || a.id.localeCompare(b.id)) * factor;
  };
}

interface HandlerOptions {
  latencyMs?: number;
}

/**
 * Handlers conforming to contracts/openapi.yaml: a valid bearer token, issued
 * by the simulated identity provider, is required on every call, and each user
 * has one private file space — another user's file answers 404.
 * `db` is injectable for tests.
 */
export function createHandlers(db: MockDb = createMockDb(), options: HandlerOptions = {}) {
  const latency = () => delay(options.latencyMs ?? 250);

  /** Resolves the caller from the bearer token, or answers 401. */
  function authenticate(request: Request): { userId: string } | Response {
    const header = request.headers.get('Authorization');
    if (!header) return problem(401, 'UNAUTHENTICATED', {}, { 'WWW-Authenticate': 'Bearer' });
    const userId = db.tokens.get(header.replace(/^Bearer\s+/i, ''));
    if (!userId) {
      return problem(401, 'UNAUTHENTICATED', {}, { 'WWW-Authenticate': 'Bearer error="invalid_token"' });
    }
    return { userId };
  }

  return [
    ...createIdpHandlers(db, { latencyMs: options.latencyMs }),

    http.get(`${API}/files`, async ({ request }) => {
      await latency();
      const caller = authenticate(request);
      if (caller instanceof Response) return caller;
      const url = new URL(request.url);
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '20');
      const sort = (url.searchParams.get('sort') ?? DEFAULT_SORT) as SortOption;
      const statuses = url.searchParams.getAll('status');
      const q = url.searchParams.get('q') ?? undefined;

      if (!SORT_OPTIONS.includes(sort) || !Number.isInteger(page) || page < 0 || size < 1 || size > 100) {
        return problem(400, 'INVALID_PARAMETER');
      }
      // Same bounds as the contract: a page at most 10 000 files deep, a search of 1 to 100 characters.
      if (page * size > MAX_DEPTH || (q !== undefined && (q.length < 1 || q.length > 100))) {
        return problem(400, 'INVALID_PARAMETER');
      }
      if (statuses.some((status) => !FILE_STATUSES.includes(status as KnownFileStatus))) {
        return problem(400, 'INVALID_PARAMETER');
      }

      const now = Date.now();
      const rows = [...db.files.values()]
        .filter((file) => isVisibleTo(file, caller.userId))
        .map((file) => toSummary(file, now))
        .filter((file) => statuses.length === 0 || statuses.includes(file.status))
        .filter((file) => !q || matches(file.filename, q))
        .sort(compare(sort));
      const totalPages = Math.ceil(rows.length / size);
      return HttpResponse.json(
        {
          content: rows.slice(page * size, page * size + size),
          page: { size, number: page, totalElements: rows.length, totalPages },
        },
        { headers: { 'Cache-Control': 'no-cache' } },
      );
    }),

    http.get(`${API}/files/summary`, async ({ request }) => {
      await latency();
      const caller = authenticate(request);
      if (caller instanceof Response) return caller;
      const q = new URL(request.url).searchParams.get('q') ?? undefined;
      const now = Date.now();
      const byStatus = Object.fromEntries(FILE_STATUSES.map((status) => [status, 0])) as Record<
        KnownFileStatus,
        number
      >;
      let total = 0;
      for (const file of db.files.values()) {
        if (!isVisibleTo(file, caller.userId)) continue;
        if (q && !matches(file.filename, q)) continue;
        byStatus[toSummary(file, now).status as KnownFileStatus] += 1;
        total += 1;
      }
      return HttpResponse.json({ total, byStatus });
    }),

    http.get(`${API}/files/:fileId`, async ({ request, params }) => {
      await latency();
      const caller = authenticate(request);
      if (caller instanceof Response) return caller;
      const file = db.files.get(String(params.fileId));
      // Another user's file answers exactly like an unknown file.
      if (!file || !isVisibleTo(file, caller.userId)) return problem(404, 'FILE_NOT_FOUND');
      const detail = toDetail(file, Date.now());
      return HttpResponse.json(detail, {
        headers: { 'Cache-Control': 'no-cache', ETag: `"${detail.status}-${detail.statusChangedAt}"` },
      });
    }),

    http.post(`${API}/files`, async ({ request }) => {
      const caller = authenticate(request);
      if (caller instanceof Response) return caller;

      const encodedName = request.headers.get('X-File-Name');
      const idempotencyKey = request.headers.get('Idempotency-Key');
      if (!encodedName) return problem(400, 'INVALID_FILE_NAME');
      let filename: string;
      try {
        filename = decodeURIComponent(encodedName);
      } catch {
        return problem(400, 'INVALID_FILE_NAME');
      }

      const declared = Number(request.headers.get('Content-Length'));
      const sizeBytes = Number.isFinite(declared) && declared > 0 ? declared : (await request.blob()).size;

      // Idempotency keys are scoped to their user. The same key for another
      // file is refused, as by the service; name and size stand in for the digest.
      const scopedKey = idempotencyKey ? `${caller.userId}:${idempotencyKey}` : null;
      if (scopedKey) {
        const existingId = db.idempotency.get(scopedKey);
        const existing = existingId ? db.files.get(existingId) : undefined;
        if (existing && (existing.filename !== filename || existing.sizeBytes !== sizeBytes)) {
          return problem(422, 'IDEMPOTENCY_KEY_REUSED');
        }
        if (existing) return HttpResponse.json(toDetail(existing, Date.now()), { status: 202 });
      }
      if (sizeBytes === 0) return problem(400, 'EMPTY_FILE');
      if (sizeBytes > MAX_FILE_SIZE_BYTES)
        return problem(413, 'FILE_TOO_LARGE', { maxFileSizeBytes: MAX_FILE_SIZE_BYTES });
      if (filename.toLowerCase().includes('surcharge')) {
        return problem(429, 'TOO_MANY_PENDING_FILES', { retryAfterSeconds: 5 }, { 'Retry-After': '5' });
      }

      await delay(options.latencyMs ?? 600);
      const file = createMockFile(filename, sizeBytes, Date.now(), caller.userId);
      db.files.set(file.id, file);
      if (scopedKey) db.idempotency.set(scopedKey, file.id);
      return HttpResponse.json(toDetail(file, Date.now()), {
        status: 202,
        headers: { Location: `${API}/files/${file.id}`, 'Retry-After': '2' },
      });
    }),
  ];
}
