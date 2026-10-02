import { type ReactNode, useCallback, useLayoutEffect, useMemo, useState } from 'react';

import { type Theme, THEME_STORAGE_KEY, ThemeContext } from '@/hooks/theme-context';

function storedTheme(): Theme {
  try {
    return localStorage.getItem(THEME_STORAGE_KEY) === 'dark' ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

/** Only the appearance preference is persisted; light is the default, regardless of OS theme. */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, updateTheme] = useState<Theme>(storedTheme);
  const setTheme = useCallback((next: Theme) => {
    updateTheme(next);
    try {
      localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch {
      // The theme still works when browser storage is unavailable.
    }
  }, []);

  // Before the paint: a change of theme never shows one frame of the other.
  // On load, `public/theme-init.js` has already set the class.
  useLayoutEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark');
  }, [theme]);

  const value = useMemo(() => ({ theme, setTheme }), [theme, setTheme]);
  return <ThemeContext value={value}>{children}</ThemeContext>;
}
