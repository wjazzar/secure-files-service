import { env } from '@/config/env';

import type { AuthAdapter } from './auth-adapter';
import { createMockAdapter } from './mock-adapter';
import { createSessionAdapter } from './session-adapter';

/**
 * The simulated sign-in exists outside builds only: in any `vite build`,
 * `import.meta.env.DEV` is a constant false, and the mock adapter is left out
 * of the bundle (audit S-12). The build itself refuses any other mode than
 * `session` (vite.config.ts).
 */
const adapters: Partial<Record<typeof env.VITE_AUTH_MODE, () => AuthAdapter>> = {
  session: () => createSessionAdapter(),
  ...(import.meta.env.DEV ? { mock: createMockAdapter } : {}),
};

function createAuth(): AuthAdapter {
  const create = adapters[env.VITE_AUTH_MODE];
  if (!create) throw new Error(`Authentication mode "${env.VITE_AUTH_MODE}" is not available in this build`);
  return create();
}

/** The authentication adapter of the application (one instance). */
export const auth: AuthAdapter = createAuth();
