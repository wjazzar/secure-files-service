import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { RouteObject } from 'react-router';

import { RequireAuth } from '@/app/require-auth';
import { FileDetailRoute } from '@/app/routes/file-detail';
import { FilesRoute } from '@/app/routes/files';
import { LoginRoute } from '@/app/routes/login';
import { AppLayout } from '@/components/layout/app-layout';
import { auth } from '@/lib/auth/auth';
import type { MockDb } from '@/testing/mocks/db';
import { installMockApi, renderWithProviders } from '@/testing/test-utils';

// The application runs with the mock identity provider in these tests.
vi.mock('@/lib/auth/auth', async () => {
  const { createMockAdapter } = await import('@/lib/auth/mock-adapter');
  return { auth: createMockAdapter() };
});

const routes: RouteObject[] = [
  { path: '/login', element: <LoginRoute /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { path: '/', element: <FilesRoute />, children: [{ path: 'files/:fileId', element: <FileDetailRoute /> }] },
        ],
      },
    ],
  },
];

let db: MockDb;

beforeEach(async () => {
  sessionStorage.clear();
  db = installMockApi();
  await auth.logout();
});

async function signInAs(username: string) {
  await auth.login({ username, password: 'demo' });
  return renderWithProviders(routes);
}

describe('login page', () => {
  it('sends a signed-out visitor to the login page, then back where they were going', async () => {
    await auth.logout();
    auth.onUnauthorized(); // no effect when already signed out
    const { router } = renderWithProviders(routes, { initialEntries: ['/?status=AVAILABLE'] });

    expect(await screen.findByRole('heading', { name: 'Connexion' })).toBeInTheDocument();
    // The mock form is loaded on demand: it never reaches a build (audit S-12).
    await userEvent.type(await screen.findByLabelText('Identifiant'), 'alice');
    await userEvent.type(screen.getByLabelText('Mot de passe'), 'demo');
    await userEvent.click(screen.getByRole('button', { name: 'Se connecter' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(router.state.location.search).toBe('?status=AVAILABLE');
  });

  it('validates the fields before calling the identity provider', async () => {
    renderWithProviders(routes, { initialEntries: ['/login'] });

    await userEvent.click(await screen.findByRole('button', { name: 'Se connecter' }));

    expect(screen.getByText('Saisissez votre identifiant.')).toBeInTheDocument();
    expect(screen.getByLabelText('Identifiant')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Identifiant')).toHaveFocus();
  });

  it('explains wrong credentials without saying which one is wrong', async () => {
    renderWithProviders(routes, { initialEntries: ['/login'] });

    await userEvent.type(await screen.findByLabelText('Identifiant'), 'alice');
    await userEvent.type(screen.getByLabelText('Mot de passe'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Se connecter' }));

    expect(await screen.findByText('Identifiant ou mot de passe incorrect.')).toBeInTheDocument();
    expect(screen.getByLabelText('Mot de passe')).toHaveValue('');
  });

  it('signs in with one click on a demo account', async () => {
    const { router } = renderWithProviders(routes, { initialEntries: ['/login'] });

    await userEvent.click(await screen.findByRole('button', { name: 'Se connecter en tant que Claire Dubois' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(await screen.findByRole('button', { name: /claire dubois/i })).toBeInTheDocument();
  });
});

describe('one private file space per user', () => {
  it('Bob only sees his own files, counters included', async () => {
    await signInAs('bob');

    expect(await screen.findByRole('link', { name: 'eicar-test.txt' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'photo-compteur-avant.jpg' })).not.toBeInTheDocument();
    const bobFiles = [...db.files.values()].filter((file) => file.ownerId === 'u-bob').length;
    const allTab = screen.getByRole('button', { name: /^tous/i });
    await waitFor(() => expect(within(allTab).getByText(String(bobFiles))).toBeInTheDocument());
  });

  it('Bob cannot open one of Alice’s files, even with its direct link', async () => {
    const aliceFile = [...db.files.values()].find((file) => file.ownerId === 'u-alice')!;
    await auth.login({ username: 'bob', password: 'demo' });
    renderWithProviders(routes, { initialEntries: [`/files/${aliceFile.id}`] });

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('Ce fichier est introuvable.')).toBeInTheDocument();
    expect(within(dialog).queryByText(aliceFile.filename)).not.toBeInTheDocument();
  });

  it('a file uploaded by Alice lands in her space only', async () => {
    await signInAs('alice');
    await screen.findByRole('link', { name: 'photo-compteur-avant.jpg' });

    await userEvent.upload(screen.getByTestId('file-input'), new File(['bonjour'], 'note-alice.pdf'));
    expect(await screen.findByRole('link', { name: 'note-alice.pdf' })).toBeInTheDocument();

    const uploaded = [...db.files.values()].find((file) => file.filename === 'note-alice.pdf');
    expect(uploaded?.ownerId).toBe('u-alice');
  });

  it('signs out from the user menu and clears what was displayed', async () => {
    const { router, queryClient } = await signInAs('alice');
    await screen.findByRole('link', { name: 'photo-compteur-avant.jpg' });

    await userEvent.click(screen.getByRole('button', { name: /alice martin/i }));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Se déconnecter' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(await screen.findByText('Vous êtes déconnecté.')).toBeInTheDocument();
    // Nothing fetched for Alice remains (the login page only loads the demo accounts).
    expect(queryClient.getQueryCache().findAll({ queryKey: ['files'] })).toHaveLength(0);
  });
});
