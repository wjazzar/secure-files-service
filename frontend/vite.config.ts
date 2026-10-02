/// <reference types="vitest/config" />
import { rmSync } from 'node:fs';
import path from 'node:path';

import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv, type Plugin } from 'vite';

// The API is reached through the dev-server proxy: the interface and the API
// share one origin, so there is no CORS and download links stay relative.
const apiTarget = process.env.API_PROXY_TARGET ?? 'http://localhost:8080';

/**
 * A build carries no mock (audit S-12), whatever its mode or output folder.
 * It refuses any sign-in mode but `session`, and any mock API; it drops the
 * MSW service worker that `public/` copies into the output. The mock modules
 * themselves are left out by dead-code elimination: in a build,
 * `import.meta.env.DEV` is a constant false (main.tsx, lib/auth/auth.ts,
 * app/routes/login.tsx).
 */
function buildWithoutMocks(): Plugin {
  let outDir = '';
  return {
    name: 'build-without-mocks',
    apply: 'build',
    configResolved(config) {
      outDir = path.resolve(config.root, config.build.outDir);
      const env = loadEnv(config.mode, config.root);
      if ((env.VITE_AUTH_MODE ?? 'session') !== 'session' || env.VITE_API_MOCKING === 'true') {
        throw new Error('A build signs in through Keycloak only: VITE_AUTH_MODE=session, no VITE_API_MOCKING.');
      }
    },
    closeBundle() {
      rmSync(path.join(outDir, 'mockServiceWorker.js'), { force: true });
    },
  };
}

export default defineConfig(() => ({
  plugins: [react(), tailwindcss(), buildWithoutMocks()],
  resolve: {
    alias: { '@': path.resolve(import.meta.dirname, './src') },
  },
  server: {
    // Explicit IPv4 loopback: on Windows, Node resolves `localhost` to ::1 only,
    // and clients that try 127.0.0.1 (proxies, Playwright, some browsers) fail.
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: false },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/testing/setup-tests.ts'],
    css: false,
    restoreMocks: true,
  },
}));
