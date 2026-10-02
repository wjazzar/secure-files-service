import { z } from 'zod';

import { safeRedirect } from '@/lib/safe-redirect';
import { isNavigableUrl } from '@/lib/safe-url';

import { type AuthAdapter, LogoutError } from './auth-adapter';
import { createAuthStore, SIGNED_OUT, timedFetch } from './auth-store';

/**
 * v2 — cookie-only session (ADR-0012). The service is Keycloak's
 * *confidential* client: it redeems the authorization code with its own
 * client secret and keeps every token on its side (the "backend for frontend"
 * pattern). This adapter therefore handles no token at all:
 *
 * - signing in is a full-page navigation to `/api/v1/auth/login`; Keycloak
 *   hosts the password page, the password never goes through the application;
 * - signing out closes the session at the service, then leaves for the
 *   address it answers — Keycloak's end-session page, which comes back to
 *   `/login?signed-out`;
 * - the session is an `HttpOnly` cookie: no script can read it, so an XSS can
 *   neither steal it nor a token (`getAccessToken` always answers `null`);
 * - writes echo the `XSRF-TOKEN` cookie in `X-XSRF-TOKEN` — axios does it for
 *   every API call (api-client.ts), `logout` does it by hand.
 */
const AUTH = '/api/v1/auth';
const XSRF_COOKIE = 'XSRF-TOKEN';
const XSRF_HEADER = 'X-XSRF-TOKEN';

/** The contract's `Session` (checked against it by files-schemas.contract.test.ts). */
export const SessionSchema = z.object({
  id: z.string(),
  username: z.string(),
  displayName: z.string(),
  email: z.string().nullable(),
});

/** The contract's `Logout`: where the browser goes once its session is closed. */
export const LogoutSchema = z.object({
  /** Followed by the browser: plain http(s) only, never `javascript:` (audit S-14). */
  logoutUrl: z.string().min(1).refine(isNavigableUrl, 'Unexpected sign-out address'),
});

function readCookie(name: string): string | null {
  const prefix = `${name}=`;
  const cookie = document.cookie.split('; ').find((candidate) => candidate.startsWith(prefix));
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : null;
}

/** `null` when the service did not answer. */
function request(path: string, method = 'GET', headers: Record<string, string> = {}): Promise<Response | null> {
  return timedFetch(`${AUTH}${path}`, {
    method,
    credentials: 'same-origin',
    headers: { Accept: 'application/json', ...headers },
  }).catch(() => null);
}

/** @param navigate leaves the page — replaced in tests */
export function createSessionAdapter(
  navigate: (url: string) => void = (url) => window.location.assign(url),
): AuthAdapter {
  const store = createAuthStore();

  return {
    loginMode: 'redirect',

    /** Asks the service who the cookie belongs to. `401`: signed out — the route guard takes it from there. */
    async init() {
      const response = await request('/session');
      if (!response?.ok) return;
      const parsed = SessionSchema.safeParse(await response.json().catch(() => null));
      if (!parsed.success) return;
      store.set({
        status: 'authenticated',
        user: {
          id: parsed.data.id,
          username: parsed.data.username,
          name: parsed.data.displayName,
          email: parsed.data.email,
        },
        reason: null,
      });
    },

    subscribe: store.subscribe,

    getSnapshot: store.getSnapshot,

    // By design: the browser holds no token. The cookie travels on its own.
    getAccessToken: () => Promise.resolve(null),

    login(_credentials, returnTo) {
      navigate(`${AUTH}/login?redirect=${encodeURIComponent(safeRedirect(returnTo))}`);
      return Promise.resolve();
    },

    async logout() {
      const xsrf = readCookie(XSRF_COOKIE);
      // The service closes its session and removes the cookie, then answers
      // where to go: Keycloak's end-session page, which ends the Keycloak
      // session too and sends the browser back to /login?signed-out.
      const response = await request('/logout', 'POST', xsrf ? { [XSRF_HEADER]: xsrf } : {});
      // 401: there was no session left to close. Anything else but a success —
      // a refused CSRF token, an error, no answer — leaves the session open at
      // the service: announcing "signed out" would be false, and on a shared
      // computer the next person would find the account open.
      if (!response || (!response.ok && response.status !== 401)) throw new LogoutError();
      const next = LogoutSchema.safeParse(response.ok ? await response.json().catch(() => null) : null);
      store.set({ ...SIGNED_OUT, reason: 'signed-out' });
      if (next.success) navigate(next.data.logoutUrl);
    },

    onUnauthorized() {
      // The service has already asked Keycloak before answering 401: the
      // session is really over, there is nothing to refresh from here.
      if (store.getSnapshot().status !== 'authenticated') return;
      store.set({ ...SIGNED_OUT, reason: 'expired' });
    },
  };
}
