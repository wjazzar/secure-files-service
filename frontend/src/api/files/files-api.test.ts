import { http, HttpResponse } from 'msw';

import { server } from '@/testing/mocks/server';

import { ApiError } from '../api-error';
import { getFile, getFiles, uploadFile } from './files-api';

describe('uploadFile', () => {
  it('sends the raw file with its encoded name and idempotency key (rules F-3, F-6, F-7)', async () => {
    let received: { headers: Headers; body: string } | undefined;
    server.use(
      http.post('*/api/v1/files', async ({ request }) => {
        received = { headers: request.headers, body: await request.text() };
        return HttpResponse.json({ error: 'stop here' }, { status: 503 });
      }),
    );
    const file = new File(['contenu'], 'relevé été 2026 #1.pdf');

    await expect(uploadFile(file, { idempotencyKey: 'key-12345678' })).rejects.toBeInstanceOf(ApiError);

    expect(received?.headers.get('Content-Type')).toBe('application/octet-stream');
    expect(received?.headers.get('X-File-Name')).toBe(encodeURIComponent('relevé été 2026 #1.pdf'));
    expect(received?.headers.get('Idempotency-Key')).toBe('key-12345678');
    expect(received?.body).toBe('contenu');
  });
});

describe('API errors', () => {
  it('turns a problem+json response into an ApiError carrying its code and Retry-After', async () => {
    server.use(
      http.get('*/api/v1/files/:id', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Unavailable', status: 503, code: 'SERVICE_UNAVAILABLE' },
          { status: 503, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '3' } },
        ),
      ),
    );

    const error = await getFile('any').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ kind: 'http', status: 503, code: 'SERVICE_UNAVAILABLE', retryAfterSeconds: 3 });
  });

  it('reports a response that does not match the contract instead of rendering it (rule F-15)', async () => {
    server.use(http.get('*/api/v1/files', () => HttpResponse.json({ items: [], nextCursor: null })));

    const error = await getFiles({ page: 0, size: 20, sort: 'uploadedAt,desc', status: [] }).catch((e: unknown) => e);

    expect(error).toMatchObject({ kind: 'invalid-response', reason: 'INVALID_RESPONSE' });
  });

  it('sends repeated status filters as the contract expects', async () => {
    let query = '';
    server.use(
      http.get('*/api/v1/files', ({ request }) => {
        query = new URL(request.url).search;
        return HttpResponse.json({ content: [], page: { size: 20, number: 0, totalElements: 0, totalPages: 0 } });
      }),
    );

    await getFiles({ page: 1, size: 20, sort: 'filename,asc', status: ['PENDING', 'SCANNING'], q: 'rapport' });

    const params = new URLSearchParams(query);
    expect(params.getAll('status')).toEqual(['PENDING', 'SCANNING']);
    expect(params.get('page')).toBe('1');
    expect(params.get('sort')).toBe('filename,asc');
    expect(params.get('q')).toBe('rapport');
  });
});
