import { PraxedoLogo } from '@/components/brand/praxedo-logo';
import { labels } from '@/i18n/messages';

export function AppFooter() {
  return (
    <footer className="workspace-footer">
      <div className="workspace-footer-inner flex flex-col gap-3 text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <PraxedoLogo className="h-4 w-auto text-foreground/70" />
          <span>{labels.footerVersion}</span>
        </div>
        <p className="workspace-footer-note sm:text-right">{labels.footer}</p>
      </div>
    </footer>
  );
}
