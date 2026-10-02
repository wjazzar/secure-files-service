import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ThemeProvider } from './theme-provider';
import { ThemeToggle } from './theme-toggle';

function renderTheme() {
  return render(
    <ThemeProvider>
      <ThemeToggle />
    </ThemeProvider>,
  );
}

beforeEach(() => {
  localStorage.clear();
  document.documentElement.classList.remove('dark');
});

afterEach(() => {
  localStorage.clear();
  document.documentElement.classList.remove('dark');
});

describe('appearance preference', () => {
  it('opens in light mode by default', () => {
    renderTheme();
    expect(screen.getByRole('button', { name: 'Clair' })).toHaveAttribute('aria-pressed', 'true');
    expect(document.documentElement).not.toHaveClass('dark');
  });

  it('switches themes using only the keyboard', async () => {
    renderTheme();
    await userEvent.tab();
    expect(screen.getByRole('button', { name: 'Clair' })).toHaveFocus();
    await userEvent.tab();
    await userEvent.keyboard('{Enter}');
    expect(screen.getByRole('button', { name: 'Sombre' })).toHaveAttribute('aria-pressed', 'true');
    expect(document.documentElement).toHaveClass('dark');
  });

  it('restores the user’s choice when the application mounts again', async () => {
    const view = renderTheme();
    await userEvent.click(screen.getByRole('button', { name: 'Sombre' }));
    view.unmount();
    renderTheme();
    expect(screen.getByRole('button', { name: 'Sombre' })).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(screen.getByRole('button', { name: 'Clair' }));
    expect(document.documentElement).not.toHaveClass('dark');
  });

  it('ignores an invalid stored preference', () => {
    localStorage.setItem('praxedo-theme', 'invalid');
    renderTheme();
    expect(screen.getByRole('button', { name: 'Clair' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('still lets the user change themes when storage is blocked', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('Storage blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('Storage blocked');
    });
    renderTheme();
    await userEvent.click(screen.getByRole('button', { name: 'Sombre' }));
    expect(screen.getByRole('button', { name: 'Sombre' })).toHaveAttribute('aria-pressed', 'true');
    expect(document.documentElement).toHaveClass('dark');
  });
});
