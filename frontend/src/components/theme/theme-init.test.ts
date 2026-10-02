import { THEME_STORAGE_KEY } from '@/hooks/theme-context';

import themeInit from '../../../public/theme-init.js?raw';

/** Runs the script the way index.html does, before anything else. */
function runThemeInit() {
  window.eval(themeInit);
}

beforeEach(() => {
  localStorage.clear();
  document.documentElement.classList.remove('dark');
});

afterEach(() => {
  localStorage.clear();
  document.documentElement.classList.remove('dark');
});

describe('theme applied before the application loads', () => {
  it('applies a stored dark preference, read under the key the provider writes', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    runThemeInit();
    expect(document.documentElement).toHaveClass('dark');
  });

  it('keeps the light default without a preference, or with an invalid one', () => {
    runThemeInit();
    expect(document.documentElement).not.toHaveClass('dark');
    localStorage.setItem(THEME_STORAGE_KEY, 'invalid');
    runThemeInit();
    expect(document.documentElement).not.toHaveClass('dark');
  });

  it('does not fail when storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('Storage blocked');
    });
    expect(runThemeInit).not.toThrow();
    expect(document.documentElement).not.toHaveClass('dark');
  });
});
