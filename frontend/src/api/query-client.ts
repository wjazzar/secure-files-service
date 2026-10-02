import { QueryClient } from '@tanstack/react-query';

import { ApiError } from './api-error';

const MAX_RETRIES = 3;

/**
 * Only transient failures are retried (network, timeout, 429, 503). A 4xx is
 * an answer, not an incident: retrying it would only repeat the refusal.
 */
function shouldRetry(failureCount: number, error: unknown): boolean {
  return failureCount < MAX_RETRIES && error instanceof ApiError && error.isRetryable;
}

/** Honors `Retry-After` when the server gives one, otherwise backs off. */
function retryDelay(attempt: number, error: unknown): number {
  if (error instanceof ApiError && error.retryAfterSeconds) return error.retryAfterSeconds * 1000;
  return Math.min(1000 * 2 ** attempt, 10_000);
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: shouldRetry, retryDelay },
      mutations: { retry: false },
    },
  });
}
