import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { LogoutError } from '@/lib/auth/auth-adapter';
import { renderWithProviders } from '@/testing/test-utils';

import { UserMenu } from './user-menu';

const logout = vi.fn<() => Promise<void>>();

vi.mock('@/lib/auth/use-auth', () => ({
  useAuth: () => ({
    status: 'authenticated',
    user: { id: 'alice-sub', username: 'alice', name: 'Alice Demo', email: null },
    reason: null,
    loginMode: 'redirect',
    login: vi.fn(),
    logout,
  }),
}));

describe('user menu', () => {
  it('keeps the user in the application, and says so, when the session could not be closed', async () => {
    logout.mockRejectedValue(new LogoutError());
    const { router } = renderWithProviders([{ path: '/', element: <UserMenu /> }]);

    await userEvent.click(screen.getByRole('button', { name: 'Alice Demo' }));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Se déconnecter' }));

    expect(await screen.findByText(/La déconnexion n’a pas abouti/)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
  });

  it('leaves for the login page once the session is closed', async () => {
    logout.mockResolvedValue();
    const { router } = renderWithProviders([
      { path: '/', element: <UserMenu /> },
      { path: '/login', element: <p>login</p> },
    ]);

    await userEvent.click(screen.getByRole('button', { name: 'Alice Demo' }));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Se déconnecter' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
  });
});
