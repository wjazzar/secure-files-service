/**
 * Authentication seam.
 *
 * The rest of the application only knows this interface. There is no mode
 * without authentication: the service refuses every request that does not
 * prove who sends it.
 * - `mock`: username/password form checked by a simulated identity provider
 *   (MSW), with demo accounts, each owning a private file space;
 * - `session`: the service is Keycloak's confidential client and the
 *   browser only holds an `HttpOnly` session cookie (ADR-0012). Keycloak hosts
 *   the login page; the password never goes through the application (the
 *   `password` grant is excluded by OAuth 2.1, RFC 9700 and RFC 10017), and no token ever
 *   reaches JavaScript.
 */
export interface AuthUser {
  id: string;
  username: string;
  name: string;
  email: string | null;
}

export interface AuthState {
  status: 'unauthenticated' | 'authenticated';
  user: AuthUser | null;
  /** Why the user is signed out, to explain it on the login page. */
  reason: 'expired' | 'signed-out' | null;
}

export interface Credentials {
  username: string;
  password: string;
}

/** Accounts offered on the login page in mock mode (never in production). */
export interface DemoAccount {
  username: string;
  password: string;
  displayName: string;
  email: string;
}

type LoginErrorCode = 'INVALID_CREDENTIALS' | 'TOO_MANY_ATTEMPTS' | 'UNAVAILABLE';

export class LoginError extends Error {
  readonly code: LoginErrorCode;
  readonly retryAfterSeconds: number | null;

  constructor(code: LoginErrorCode, retryAfterSeconds: number | null = null) {
    super(code);
    this.name = 'LoginError';
    this.code = code;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** The service did not close the session: the user is still signed in, and must be told so. */
export class LogoutError extends Error {
  constructor() {
    super('LOGOUT_FAILED');
    this.name = 'LogoutError';
  }
}

export interface AuthAdapter {
  /** `form`: this application shows the login form. `redirect`: the identity provider hosts it. */
  readonly loginMode: 'form' | 'redirect';
  /** Called once, before React renders (main.tsx): restores an existing session. */
  init: () => Promise<void>;
  /** External-store contract, read through `useSyncExternalStore`. */
  subscribe: (listener: () => void) => () => void;
  getSnapshot: () => AuthState;
  /** Access token to send; `null` when none — always, with a session cookie. Never persisted (F-13). */
  getAccessToken: () => Promise<string | null>;
  /**
   * Throws {@link LoginError}. In `redirect` mode, credentials are ignored and
   * the page leaves for the identity provider, to come back to `returnTo`.
   */
  login: (credentials?: Credentials, returnTo?: string) => Promise<void>;
  /** Throws {@link LogoutError} when the session could not be closed: the user stays signed in. */
  logout: () => Promise<void>;
  /** The API answered 401: the session is over. */
  onUnauthorized: () => void;
  /** Mock mode only. */
  demoAccounts?: () => Promise<DemoAccount[]>;
}
