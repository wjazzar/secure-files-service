import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { RouteObject } from 'react-router';

import { RequireAuth } from '@/app/require-auth';
import { LoginRoute } from '@/app/routes/login';
import { server } from '@/testing/mocks/server';
import { renderWithProviders } from '@/testing/test-utils';

const navigate = vi.hoisted(() => vi.fn());

// The application runs with the v2 adapter: Keycloak through the service.
vi.mock('@/lib/auth/auth', async () => {
  const { createSessionAdapter } = await import('@/lib/auth/session-adapter');
  return { auth: createSessionAdapter(navigate) };
});

const routes: RouteObject[] = [
  { path: '/login', element: <LoginRoute /> },
  { element: <RequireAuth />, children: [{ path: '/', element: <p>Espace de fichiers</p> }] },
];

beforeEach(() => {
  navigate.mockReset();
  server.use(http.get('*/api/v1/auth/session', () => HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 })));
});

describe('login page, v2 (Keycloak through the service)', () => {
  it('offers a single button — no password field: Keycloak hosts the password page', async () => {
    renderWithProviders(routes, { initialEntries: ['/?status=AVAILABLE'] });

    expect(await screen.findByRole('heading', { name: 'Connexion' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Mot de passe')).not.toBeInTheDocument();
    expect(screen.getByText(/Aucun jeton dans le navigateur/)).toBeInTheDocument();
  });

  it('sends the browser to the service’s sign-in, remembering where it was going', async () => {
    renderWithProviders(routes, { initialEntries: ['/?status=AVAILABLE'] });

    await userEvent.click(await screen.findByRole('button', { name: 'Se connecter' }));

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/api/v1/auth/login?redirect=%2F%3Fstatus%3DAVAILABLE'));
    expect(screen.getByRole('button', { name: 'Redirection…' })).toBeDisabled();
  });

  it('explains a sign-in that did not complete, without saying why', async () => {
    renderWithProviders(routes, { initialEntries: ['/login?error=sign-in-failed'] });

    expect(await screen.findByRole('alert')).toHaveTextContent('La connexion n’a pas abouti. Réessayez.');
  });

  it('confirms the sign-out on the way back from Keycloak’s end-session page', async () => {
    renderWithProviders(routes, { initialEntries: ['/login?signed-out'] });

    expect(await screen.findByText('Vous êtes déconnecté.')).toBeInTheDocument();
  });
});
