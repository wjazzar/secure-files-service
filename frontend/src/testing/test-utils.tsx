import { QueryClient } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { createMemoryRouter, type RouteObject, RouterProvider } from 'react-router';

import { AppProvider } from '@/app/provider';

import { createMockDb, type MockDb } from './mocks/db';
import { createHandlers } from './mocks/handlers';
import { server } from './mocks/server';

function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false } },
  });
}

/** Installs the contract-conforming mock API on a fresh database; every call needs a signed-in user. */
export function installMockApi(db: MockDb = createMockDb()): MockDb {
  server.use(...createHandlers(db, { latencyMs: 0 }));
  return db;
}

/** Renders `routes` (or a single element at `/`) with every application provider. */
export function renderWithProviders(
  ui: ReactElement | RouteObject[],
  { initialEntries = ['/'] }: { initialEntries?: string[] } = {},
) {
  const queryClient = createTestQueryClient();
  const routes: RouteObject[] = Array.isArray(ui) ? ui : [{ path: '*', element: ui }];
  const router = createMemoryRouter(routes, { initialEntries });
  const result = render(
    <AppProvider queryClient={queryClient}>
      <RouterProvider router={router} />
    </AppProvider>,
  );
  return { ...result, queryClient, router };
}
