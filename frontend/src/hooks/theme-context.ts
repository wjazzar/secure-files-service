import { createContext } from 'react';

export type Theme = 'light' | 'dark';

/** Also read by `public/theme-init.js`, which runs before the application loads. */
export const THEME_STORAGE_KEY = 'praxedo-theme';

export const ThemeContext = createContext<{ theme: Theme; setTheme: (theme: Theme) => void } | null>(null);
