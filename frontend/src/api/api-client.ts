import axios from 'axios';
import type { z } from 'zod';

import { auth } from '@/lib/auth/auth';

import { ApiError, toApiError } from './api-error';

/**
 * The single HTTP client of the application. `axios` is imported nowhere else
 * (ESLint rule): components and hooks only see typed functions and ApiError.
 *
 * The interface and the API share one origin, through the Vite proxy: no
 * CORS, relative URLs.
 */
export const apiClient = axios.create({
  baseURL: '/api/v1',
  // Every JSON call has an explicit timeout. Uploads override it (see files-api).
  timeout: 15_000,
  headers: { Accept: 'application/json' },
  // `status=A&status=B`, as the contract expects (not `status[]=A`).
  paramsSerializer: { indexes: null },
  // v2, session cookie: every write echoes the readable XSRF-TOKEN cookie in
  // this header, which another site cannot do. Axios does it for same-origin
  // requests only — the token never leaves for another host. These are
  // axios's defaults, spelled out because the service relies on them.
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
});

// Authentication seam: the adapter provides the token, if any — none with a
// session cookie, which the browser sends on its own. Nothing else in the
// application handles credentials.
apiClient.interceptors.request.use(async (config) => {
  const token = await auth.getAccessToken();
  if (token) config.headers.set('Authorization', `Bearer ${token}`);
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const apiError = toApiError(error);
    // The session is over (expired, revoked, ended in Keycloak — the service
    // has already tried to revalidate it): the adapter signs the user out and
    // the route guard sends them to the login page.
    if (apiError.status === 401) auth.onUnauthorized();
    return Promise.reject(apiError);
  },
);

/**
 * Validates a response body against its schema (rule F-15). A body that does
 * not match the contract becomes an `invalid-response` error instead of an
 * inconsistent screen.
 */
export function parseResponse<T extends z.ZodType>(schema: T, data: unknown): z.infer<T> {
  const result = schema.safeParse(data);
  if (!result.success) {
    if (import.meta.env.DEV) console.error('Response does not match the API contract', result.error.issues);
    throw new ApiError({ kind: 'invalid-response', cause: result.error });
  }
  return result.data;
}
