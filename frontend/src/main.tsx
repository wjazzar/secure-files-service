import './styles/globals.css';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { App } from '@/app/app';
import { env } from '@/config/env';
import { auth } from '@/lib/auth/auth';

async function bootstrap(): Promise<void> {
  // `import.meta.env.DEV` is replaced by a constant at build time: in any
  // build this branch is dead, and the mock API (MSW, simulated identity
  // provider, demo accounts) is left out of the bundle (audit S-12).
  if (import.meta.env.DEV && env.VITE_API_MOCKING) {
    try {
      const { startMockWorker } = await import('@/testing/mocks/browser');
      await startMockWorker();
    } catch (error) {
      // Render anyway: without the mock API, requests fail and the interface
      // shows its error states instead of a blank page.
      console.error('Mock API (MSW) could not start; requests will reach the real API.', error);
    }
  }
  // Authentication is initialized before React renders: the tree never has
  // to handle an "initializing" state, and React 19 Strict Mode cannot
  // initialize it twice.
  await auth.init();

  const container = document.getElementById('root');
  if (!container) throw new Error('Missing #root element');
  createRoot(container).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void bootstrap();
