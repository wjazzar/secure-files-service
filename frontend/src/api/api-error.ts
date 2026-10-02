import { AxiosError, isAxiosError, isCancel } from 'axios';

import { type ErrorCode, ProblemSchema } from './problem-schema';
import { UNKNOWN } from './tolerant-enum';

type ApiErrorKind = 'http' | 'network' | 'timeout' | 'canceled' | 'invalid-response';

/**
 * What went wrong, as the interface needs to know it: an API error code, or a
 * transport failure that never reached the API.
 */
export type ErrorReason = ErrorCode | 'NETWORK_ERROR' | 'TIMEOUT' | 'INVALID_RESPONSE' | 'CANCELED';

/**
 * The only error type the rest of the application ever sees. Axios errors are
 * converted at the API boundary, so no component depends on axios.
 */
export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number | null;
  readonly code: ErrorCode;
  readonly retryAfterSeconds: number | null;

  constructor(init: {
    kind: ApiErrorKind;
    status?: number | null;
    code?: ErrorCode;
    retryAfterSeconds?: number | null;
    cause?: unknown;
  }) {
    super(`${init.kind}:${init.code ?? UNKNOWN}`, { cause: init.cause });
    this.name = 'ApiError';
    this.kind = init.kind;
    this.status = init.status ?? null;
    this.code = init.code ?? UNKNOWN;
    this.retryAfterSeconds = init.retryAfterSeconds ?? null;
  }

  get reason(): ErrorReason {
    switch (this.kind) {
      case 'network':
        return 'NETWORK_ERROR';
      case 'timeout':
        return 'TIMEOUT';
      case 'invalid-response':
        return 'INVALID_RESPONSE';
      case 'canceled':
        return 'CANCELED';
      case 'http':
        return this.code;
    }
  }

  /** Transient failure worth retrying (same request, later). */
  get isRetryable(): boolean {
    if (this.kind === 'network' || this.kind === 'timeout') return true;
    return (
      this.status === 429 ||
      this.status === 503 ||
      this.code === 'IDEMPOTENCY_REQUEST_IN_PROGRESS' ||
      this.code === 'UPLOAD_TOO_SLOW'
    );
  }
}

function parseRetryAfter(header: unknown): number | null {
  if (typeof header !== 'string') return null;
  const seconds = Number.parseInt(header, 10);
  return Number.isFinite(seconds) && seconds > 0 ? seconds : null;
}

/** Converts anything thrown by an API call into an {@link ApiError}. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error;
  if (isCancel(error)) return new ApiError({ kind: 'canceled', cause: error });
  if (!isAxiosError(error)) return new ApiError({ kind: 'network', cause: error });

  if (error.code === AxiosError.ECONNABORTED || error.code === AxiosError.ETIMEDOUT) {
    return new ApiError({ kind: 'timeout', cause: error });
  }
  const response = error.response;
  if (!response) return new ApiError({ kind: 'network', cause: error });

  const parsed = ProblemSchema.safeParse(response.data);
  const problem = parsed.success ? parsed.data : null;
  return new ApiError({
    kind: 'http',
    status: response.status,
    code: problem?.code ?? fallbackCode(response.status),
    retryAfterSeconds: problem?.retryAfterSeconds ?? parseRetryAfter(response.headers['retry-after']),
    cause: error,
  });
}

/** Code used when an error response carries no problem body (proxy, gateway…). */
function fallbackCode(status: number): ErrorCode {
  if (status === 401) return 'UNAUTHENTICATED';
  if (status === 404) return 'FILE_NOT_FOUND';
  if (status === 413) return 'FILE_TOO_LARGE';
  if (status === 502 || status === 503 || status === 504) return 'SERVICE_UNAVAILABLE';
  if (status >= 500) return 'INTERNAL_ERROR';
  return UNKNOWN;
}
