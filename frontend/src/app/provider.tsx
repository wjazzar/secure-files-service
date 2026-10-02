import { type QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

import { LiveRegionProvider } from '@/components/live-region';
import { ThemeProvider } from '@/components/theme/theme-provider';
import { Toaster } from '@/components/ui/sonner';

/** Application-wide providers. Tests reuse it with their own QueryClient. */
export function AppProvider({ queryClient, children }: { queryClient: QueryClient; children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <LiveRegionProvider>
          {children}
          <Toaster position="bottom-right" closeButton />
        </LiveRegionProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
