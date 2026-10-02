import { FlaskConicalIcon, ShieldCheckIcon } from 'lucide-react';
import { Link } from 'react-router';

import { PraxedoLogo } from '@/components/brand/praxedo-logo';
import { ThemeToggle } from '@/components/theme/theme-toggle';
import { env } from '@/config/env';
import { labels } from '@/i18n/messages';
import { mockLabels } from '@/i18n/mock-messages';

import { UserMenu } from './user-menu';

export function AppHeader() {
  return (
    <header className="workspace-header text-header-foreground">
      <div className="workspace-header-inner">
        <Link
          to="/"
          className="workspace-brand flex items-center rounded-md focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
        >
          <PraxedoLogo className="workspace-logo text-header-foreground" />
          <span aria-hidden className="hidden h-6 w-px bg-border sm:block" />
          <span className="workspace-brand-caption hidden items-center gap-1.5 text-header-foreground/85 md:flex">
            <ShieldCheckIcon aria-hidden className="size-3.5 text-primary" />
            {labels.appTitle}
          </span>
        </Link>
        <div className="workspace-header-actions flex items-center">
          {import.meta.env.DEV && env.VITE_API_MOCKING && (
            <span
              title={mockLabels.mockBadgeHint}
              className="workspace-mock-badge hidden items-center gap-1.5 rounded-full border bg-card px-2.5 py-1 text-muted-foreground lg:inline-flex"
            >
              <FlaskConicalIcon aria-hidden className="size-3.5 text-primary" />
              {mockLabels.mockBadge}
            </span>
          )}
          <ThemeToggle />
          <UserMenu />
        </div>
      </div>
    </header>
  );
}
