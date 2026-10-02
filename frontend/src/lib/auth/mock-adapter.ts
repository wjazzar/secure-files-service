import { z } from 'zod';

import { type AuthAdapter, type Credentials, type DemoAccount, LoginError, LogoutError } from './auth-adapter';
import { createAuthStore, SIGNED_OUT, timedFetch } from './auth-store';
import { SessionSchema } from './session-adapter';

/**
 * Mock mode: talks to a simulated identity provider (MSW handlers under
 * `/mock-idp`), with demo accounts. Outside production builds only (auth.ts).
 *
 * - The access token stays in this closure: memory only (F-13).
 * - The identity provider keeps its own session, like Keycloak's SSO cookie:
 *   a page reload gets a new token without typing the password again.
 *
 * The schemas are built in the factory, not at the top of the module: a
 * module without top-level side effects is left out of a bundle that does not
 * call it, and a production build never does.
 */
const IDP = '/mock-idp';

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  try {
    return await timedFetch(`${IDP}${path}`, {
      ...init,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    });
  } catch {
    throw new LoginError('UNAVAILABLE');
  }
}

export function createMockAdapter(): AuthAdapter {
  // The simulated provider answers with the same user as the service's `/session`.
  const TokenResponseSchema = z.object({
    accessToken: z.string().min(1),
    user: SessionSchema,
  });
  const DemoAccountsSchema = z.array(
    z.object({
      username: z.string(),
      password: z.string(),
      displayName: z.string(),
      email: z.string(),
    }),
  );

  let token: string | null = null;
  const store = createAuthStore();

  const signIn = (payload: z.infer<typeof TokenResponseSchema>) => {
    token = payload.accessToken;
    store.set({
      status: 'authenticated',
      user: {
        id: payload.user.id,
        username: payload.user.username,
        name: payload.user.displayName,
        email: payload.user.email,
      },
      reason: null,
    });
  };

  return {
    loginMode: 'form',

    async init() {
      const response = await request('/session').catch(() => null);
      if (!response?.ok) return;
      const parsed = TokenResponseSchema.safeParse(await response.json());
      if (parsed.success) signIn(parsed.data);
    },

    subscribe: store.subscribe,

    getSnapshot: store.getSnapshot,

    getAccessToken: () => Promise.resolve(token),

    async login(credentials?: Credentials) {
      if (!credentials) throw new LoginError('INVALID_CREDENTIALS');
      const response = await request('/login', { method: 'POST', body: JSON.stringify(credentials) });
      if (response.status === 401) throw new LoginError('INVALID_CREDENTIALS');
      if (response.status === 429) {
        const retryAfter = Number.parseInt(response.headers.get('Retry-After') ?? '', 10);
        throw new LoginError('TOO_MANY_ATTEMPTS', Number.isFinite(retryAfter) ? retryAfter : null);
      }
      if (!response.ok) throw new LoginError('UNAVAILABLE');
      const parsed = TokenResponseSchema.safeParse(await response.json());
      if (!parsed.success) throw new LoginError('UNAVAILABLE');
      signIn(parsed.data);
    },

    async logout() {
      // Same rule as the real session: signed out only once the provider has closed its session.
      const response = await request('/logout', { method: 'POST' }).catch(() => null);
      if (!response?.ok) throw new LogoutError();
      token = null;
      store.set({ ...SIGNED_OUT, reason: 'signed-out' });
    },

    onUnauthorized() {
      if (store.getSnapshot().status !== 'authenticated') return;
      token = null;
      store.set({ ...SIGNED_OUT, reason: 'expired' });
    },

    async demoAccounts(): Promise<DemoAccount[]> {
      const response = await request('/accounts');
      if (!response.ok) return [];
      const parsed = DemoAccountsSchema.safeParse(await response.json());
      return parsed.success ? parsed.data : [];
    },
  };
}
