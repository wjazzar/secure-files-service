import { setupWorker } from 'msw/browser';

import { createHandlers } from './handlers';

/**
 * Mock API in the browser (`npm run dev:mock`): the interface runs without
 * the backend. Like the real service, the API requires a token — issued here
 * by the simulated identity provider (`VITE_AUTH_MODE=mock`) — and each
 * account only sees its own files.
 *
 * Note: in this mode the service worker receives request bodies, so test
 * uploads with small files; large uploads are checked against the real backend.
 */
export async function startMockWorker(): Promise<void> {
  const worker = setupWorker(...createHandlers());
  await worker.start({ onUnhandledRequest: 'bypass', quiet: true });
}
