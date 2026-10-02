import { useState } from 'react';
import { RouterProvider } from 'react-router';

import { createQueryClient } from '@/api/query-client';

import { AppProvider } from './provider';
import { createAppRouter } from './router';

export function App() {
  const [queryClient] = useState(createQueryClient);
  const [router] = useState(createAppRouter);
  return (
    <AppProvider queryClient={queryClient}>
      <RouterProvider router={router} />
    </AppProvider>
  );
}
