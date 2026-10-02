import { useSyncExternalStore } from 'react';

import { auth } from './auth';
import type { AuthState } from './auth-adapter';

/** Current authentication state; re-renders when it changes. */
export function useAuth(): AuthState & {
  loginMode: typeof auth.loginMode;
  login: typeof auth.login;
  logout: typeof auth.logout;
} {
  const state = useSyncExternalStore(auth.subscribe, auth.getSnapshot);
  return { ...state, loginMode: auth.loginMode, login: auth.login, logout: auth.logout };
}
