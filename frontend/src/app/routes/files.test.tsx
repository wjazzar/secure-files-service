import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { FileDetailRoute } from '@/app/routes/file-detail';
import { FilesRoute } from '@/app/routes/files';
import { auth } from '@/lib/auth/auth';
import { createMockDb, createMockFile, type MockDb } from '@/testing/mocks/db';
import { installMockApi, renderWithProviders } from '@/testing/test-utils';

// Every call to the API needs a signed-in user: Alice, through the mock identity provider.
vi.mock('@/lib/auth/auth', async () => {
  const { createMockAdapter } = await import('@/lib/auth/mock-adapter');
  return { auth: createMockAdapter() };
});

async function installAsAlice(db: MockDb) {
  installMockApi(db);
  await auth.login({ username: 'alice', password: 'demo' });
}

const routes = [
  {
    path: '/',
    element: <FilesRoute />,
    children: [{ path: 'files/:fileId', element: <FileDetailRoute /> }],
  },
];

function seededDb() {
  const db = createMockDb();
  db.files.clear();
  const old = Date.now() - 3_600_000;
  for (const [name, age] of [
    ['rapport-a.pdf', 1],
    ['eicar.txt', 2],
    ['photo.jpg', 3],
  ] as const) {
    const file = createMockFile(name, 2048, old - age * 1000, 'u-alice');
    db.files.set(file.id, file);
  }
  return db;
}

describe('files page', () => {
  it('lists the files with their status, and only offers downloads the server allows', async () => {
    await installAsAlice(seededDb());
    renderWithProviders(routes);

    const table = await screen.findByRole('table');
    const eicarRow = (await within(table).findByRole('link', { name: 'eicar.txt' })).closest('tr');
    const reportRow = within(table).getByRole('link', { name: 'rapport-a.pdf' }).closest('tr');

    expect(within(eicarRow!).getByText('Menace détectée')).toBeInTheDocument();
    expect(within(eicarRow!).queryByRole('button', { name: /télécharger/i })).not.toBeInTheDocument();
    expect(within(reportRow!).getByRole('button', { name: /télécharger rapport-a.pdf/i })).toBeInTheDocument();
  });

  it('searches by name and goes back to the first page', async () => {
    await installAsAlice(seededDb());
    const { router } = renderWithProviders(routes, { initialEntries: ['/?page=1'] });
    await screen.findByRole('link', { name: 'photo.jpg' });

    await userEvent.type(screen.getByRole('searchbox', { name: /rechercher par nom/i }), 'rapport');

    await waitFor(() => expect(screen.queryByRole('link', { name: 'photo.jpg' })).not.toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'rapport-a.pdf' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?q=rapport');
  });

  it('opens the detail panel from the table, and explains why an infected file is blocked', async () => {
    await installAsAlice(seededDb());
    renderWithProviders(routes);

    await userEvent.click(await screen.findByRole('link', { name: 'eicar.txt' }));

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('Ce fichier est bloqué')).toBeInTheDocument();
    expect(within(dialog).getByText('Eicar-Test-Signature')).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: /télécharger/i })).not.toBeInTheDocument();
  });

  it('uploads a file, which then appears in the table as waiting for analysis', async () => {
    await installAsAlice(seededDb());
    renderWithProviders(routes);
    await screen.findByRole('link', { name: 'photo.jpg' });

    await userEvent.upload(screen.getByTestId('file-input'), new File(['bonjour'], 'nouveau.pdf'));

    expect(await screen.findByText('Envoyé — analyse en cours')).toBeInTheDocument();
    const row = (await screen.findByRole('link', { name: 'nouveau.pdf' })).closest('tr');
    expect(within(row!).getByText('En attente d’analyse')).toBeInTheDocument();
  });

  it('filters the table on the blocked statuses from the summary card', async () => {
    await installAsAlice(seededDb());
    const { router } = renderWithProviders(routes);
    await screen.findByRole('link', { name: 'photo.jpg' });

    const blocked = screen.getByRole('button', { name: /bloqués/i });
    await waitFor(() => expect(within(blocked).getByText('1')).toBeInTheDocument());
    await userEvent.click(blocked);

    expect(blocked).toHaveAttribute('aria-pressed', 'true');
    expect(new URLSearchParams(router.state.location.search).getAll('status')).toEqual([
      'INFECTED',
      'UNSCANNABLE',
      'FAILED',
    ]);
    await waitFor(() => expect(screen.queryByRole('link', { name: 'photo.jpg' })).not.toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'eicar.txt' })).toBeInTheDocument();
  });

  it('clears the search and the filter in one click from an empty result', async () => {
    await installAsAlice(seededDb());
    const { router } = renderWithProviders(routes, { initialEntries: ['/?q=introuvable&status=AVAILABLE'] });

    await userEvent.click(await screen.findByRole('button', { name: /voir tous les fichiers/i }));

    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(await screen.findByRole('link', { name: 'photo.jpg' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /tous/i })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('searchbox', { name: /rechercher par nom/i })).toHaveValue('');
  });
});
