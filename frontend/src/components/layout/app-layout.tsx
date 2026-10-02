import { Outlet } from 'react-router';

import { labels } from '@/i18n/messages';

import { AppFooter } from './app-footer';
import { AppHeader } from './app-header';

import '@/styles/workspace.css';

export function AppLayout() {
  return (
    <div className="workspace-shell flex min-h-svh flex-col">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-card focus:px-3 focus:py-2 focus:shadow"
      >
        {labels.skipToContent}
      </a>
      <AppHeader />
      <main id="main" className="flex-1 pb-12">
        <Outlet />
      </main>
      <AppFooter />
    </div>
  );
}
