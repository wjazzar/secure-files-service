import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import * as browserDownload from '@/lib/browser-download';
import { createMockFile, toDetail } from '@/testing/mocks/db';
import { server } from '@/testing/mocks/server';
import { renderWithProviders } from '@/testing/test-utils';

import { DownloadButton } from './download-button';

const file = { id: '5b1f6f2e-7d2a-4b8e-9d3c-0f6f3c1b2a11', filename: 'rapport.pdf' };

/** The server's current view of the file, long after its analysis ended. */
function detailOf(filename: string) {
  const now = Date.now();
  return toDetail(createMockFile(filename, 1_024, now - 60_000, 'u-alice', file.id), now);
}

describe('DownloadButton', () => {
  it('does not exist when the server says the file is not downloadable (rule F-1)', () => {
    renderWithProviders(<DownloadButton file={{ ...file, downloadable: false }} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('reads the file again at click time, then hands the content URL it gives to the browser (rules F-4, F-5)', async () => {
    const start = vi.spyOn(browserDownload, 'startBrowserDownload').mockImplementation(() => undefined);
    const content = `/api/v1/files/${file.id}/content`;
    server.use(
      http.get('*/api/v1/files/:id', () =>
        HttpResponse.json({ ...detailOf('rapport.pdf'), links: { self: `/api/v1/files/${file.id}`, content } }),
      ),
    );
    renderWithProviders(<DownloadButton file={{ ...file, downloadable: true }} />);

    await userEvent.click(await screen.findByRole('button', { name: /télécharger/i }));

    await vi.waitFor(() => expect(start).toHaveBeenCalledWith(content, 'rapport.pdf'));
  });

  it('explains why a file that stopped being downloadable is refused, and does not download', async () => {
    const start = vi.spyOn(browserDownload, 'startBrowserDownload').mockImplementation(() => undefined);
    server.use(
      http.get('*/api/v1/files/:id', () => HttpResponse.json(detailOf('eicar.com'))),
      http.get('*/api/v1/files', () => new HttpResponse(null, { status: 404 })),
    );
    renderWithProviders(<DownloadButton file={{ ...file, downloadable: true }} />);

    await userEvent.click(await screen.findByRole('button', { name: /télécharger/i }));

    expect(await screen.findByText(/une menace a été détectée/i)).toBeInTheDocument();
    expect(start).not.toHaveBeenCalled();
  });
});
