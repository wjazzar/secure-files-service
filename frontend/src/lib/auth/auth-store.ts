import type { AuthState } from './auth-adapter';

export const SIGNED_OUT: AuthState = { status: 'unauthenticated', user: null, reason: null };

/** How long a call to the identity side may take before it counts as unanswered. */
const TIMEOUT_MS = 10_000;

/** The state each adapter keeps, read by React through `useSyncExternalStore` (use-auth.ts). */
export function createAuthStore() {
  let state: AuthState = SIGNED_OUT;
  const listeners = new Set<() => void>();
  return {
    getSnapshot: (): AuthState => state,
    subscribe: (listener: () => void): (() => void) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    set: (next: AuthState): void => {
      state = next;
      listeners.forEach((listener) => listener());
    },
  };
}

/** A same-origin call with a timeout. Absolute URL: it works in the browser and in the tests alike. */
export function timedFetch(path: string, init: RequestInit = {}): Promise<Response> {
  return fetch(new URL(path, window.location.origin), { ...init, signal: AbortSignal.timeout(TIMEOUT_MS) });
}
