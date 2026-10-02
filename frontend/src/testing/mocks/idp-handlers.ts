import { delay, http, HttpResponse } from 'msw';

import type { MockDb } from './db';
import { findUserById, findUserByUsername, MOCK_USERS, type MockUser } from './users';

/**
 * Simulated identity provider, standing in for Keycloak in mock mode.
 *
 * - Issues opaque access tokens, recorded in `db.tokens` so that the API
 *   handlers can resolve the caller.
 * - Keeps an "SSO session" in sessionStorage, like Keycloak's session cookie:
 *   a page reload gets a new token without typing the password again.
 * - Locks an account for 30 s after 5 failed attempts.
 */
const IDP = '/mock-idp';
const SESSION_KEY = 'praxedo.mock-idp.session';
const MAX_ATTEMPTS = 5;
const LOCK_MS = 30_000;

function readSession(): string | null {
  try {
    return sessionStorage.getItem(SESSION_KEY);
  } catch {
    return null;
  }
}

function writeSession(userId: string | null): void {
  try {
    if (userId) sessionStorage.setItem(SESSION_KEY, userId);
    else sessionStorage.removeItem(SESSION_KEY);
  } catch {
    // Storage unavailable: the session simply does not survive a reload.
  }
}

function issueToken(db: MockDb, user: MockUser) {
  const accessToken = `mock.${user.id}.${crypto.randomUUID()}`;
  db.tokens.set(accessToken, user.id);
  return {
    accessToken,
    user: { id: user.id, username: user.username, displayName: user.displayName, email: user.email },
  };
}

export function createIdpHandlers(db: MockDb, options: { latencyMs?: number } = {}) {
  const latency = () => delay(options.latencyMs ?? 350);

  return [
    http.post(`${IDP}/login`, async ({ request }) => {
      await latency();
      const body = (await request.json().catch(() => null)) as { username?: unknown; password?: unknown } | null;
      const username = typeof body?.username === 'string' ? body.username.trim().toLowerCase() : '';
      const password = typeof body?.password === 'string' ? body.password : '';

      const attempts = db.failedLogins.get(username);
      if (attempts && attempts.lockedUntil > Date.now()) {
        const retryAfter = Math.ceil((attempts.lockedUntil - Date.now()) / 1000);
        return HttpResponse.json(
          { error: 'too_many_attempts' },
          { status: 429, headers: { 'Retry-After': `${retryAfter}` } },
        );
      }

      const user = findUserByUsername(username);
      // Same answer for an unknown user and a wrong password: no account enumeration.
      if (user?.password !== password) {
        const count = (attempts?.count ?? 0) + 1;
        db.failedLogins.set(username, {
          count: count >= MAX_ATTEMPTS ? 0 : count,
          lockedUntil: count >= MAX_ATTEMPTS ? Date.now() + LOCK_MS : 0,
        });
        return HttpResponse.json({ error: 'invalid_grant' }, { status: 401 });
      }

      db.failedLogins.delete(username);
      writeSession(user.id);
      return HttpResponse.json(issueToken(db, user));
    }),

    http.get(`${IDP}/session`, () => {
      const user = findUserById(readSession() ?? '');
      if (!user) return HttpResponse.json({ error: 'login_required' }, { status: 401 });
      return HttpResponse.json(issueToken(db, user));
    }),

    http.post(`${IDP}/logout`, () => {
      const userId = readSession();
      writeSession(null);
      // Revokes every token of the user: a stolen token stops working.
      for (const [token, owner] of db.tokens) if (owner === userId) db.tokens.delete(token);
      return new HttpResponse(null, { status: 204 });
    }),

    http.get(`${IDP}/accounts`, () =>
      HttpResponse.json(
        MOCK_USERS.map(({ username, password, displayName, email }) => ({ username, password, displayName, email })),
      ),
    ),
  ];
}
