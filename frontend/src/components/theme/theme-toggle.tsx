import { MoonIcon, SunIcon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { useTheme } from '@/hooks/use-theme';
import { labels } from '@/i18n/messages';

/** Two explicit choices, keyboard accessible, shared by login and the file workspace. */
export function ThemeToggle() {
  const { theme, setTheme } = useTheme();
  return (
    <div className="theme-toggle" role="group" aria-label={labels.theme.label}>
      <Button
        type="button"
        variant="ghost"
        className="theme-toggle-option"
        aria-label={labels.theme.light}
        aria-pressed={theme === 'light'}
        onClick={() => setTheme('light')}
      >
        <SunIcon aria-hidden="true" />
        <span className="theme-toggle-label">{labels.theme.light}</span>
      </Button>
      <Button
        type="button"
        variant="ghost"
        className="theme-toggle-option"
        aria-label={labels.theme.dark}
        aria-pressed={theme === 'dark'}
        onClick={() => setTheme('dark')}
      >
        <MoonIcon aria-hidden="true" />
        <span className="theme-toggle-label">{labels.theme.dark}</span>
      </Button>
    </div>
  );
}
