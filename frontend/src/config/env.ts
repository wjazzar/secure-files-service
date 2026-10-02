import { z } from 'zod';

const MEGABYTE = 1024 * 1024;

/**
 * Build-time configuration, validated once at startup.
 *
 * An invalid value fails fast with an explicit message instead of producing a
 * subtly broken interface later.
 */
const EnvSchema = z.object({
  /** Starts the MSW mock API in the browser (`npm run dev:mock`). */
  VITE_API_MOCKING: z
    .enum(['true', 'false'])
    .default('false')
    .transform((value) => value === 'true'),
  /**
   * Largest accepted file. Mirrors the server limit (500 MB) so that an
   * oversized file is refused before being sent; the server stays the
   * authority and answers 413 anyway.
   */
  VITE_MAX_UPLOAD_BYTES: z.coerce
    .number()
    .int()
    .positive()
    .default(500 * MEGABYTE),
  /**
   * Authentication mode (see lib/auth) — there is none without authentication:
   * `session` (default) = Keycloak through the service (confidential client,
   * `HttpOnly` session cookie); `mock` = login form + simulated identity
   * provider with demo accounts, to run against the mock API.
   */
  VITE_AUTH_MODE: z.enum(['session', 'mock']).default('session'),
});

type Env = z.infer<typeof EnvSchema>;

function parseEnv(): Env {
  const result = EnvSchema.safeParse(import.meta.env);
  if (!result.success) {
    throw new Error(`Invalid configuration:\n${z.prettifyError(result.error)}`);
  }
  return result.data;
}

export const env = parseEnv();
