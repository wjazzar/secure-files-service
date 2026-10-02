import { createMockDb } from './db';
import { installMockApi } from '../test-utils';

/** The mock API refuses what the service refuses: tests written against it hold against the real one. */
describe('mock API, held to the contract', () => {
  const TOKEN = 'token-alice';

  function call(path: string, init: RequestInit = {}) {
    const headers = new Headers(init.headers);
    headers.set('Authorization', `Bearer ${TOKEN}`);
    return fetch(new URL(path, window.location.origin), { ...init, headers });
  }

  function upload(name: string, body: string, key: string) {
    return call('/api/v1/files', {
      method: 'POST',
      body,
      headers: { 'X-File-Name': encodeURIComponent(name), 'Idempotency-Key': key },
    });
  }

  beforeEach(() => {
    const db = createMockDb();
    db.tokens.set(TOKEN, 'u-alice');
    installMockApi(db);
  });

  it.each([
    ['a page more than 10 000 files deep', '/api/v1/files?page=501&size=20'],
    ['a search longer than 100 characters', `/api/v1/files?q=${'a'.repeat(101)}`],
  ])('400 for %s', async (_, path) => {
    const response = await call(path);

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({ code: 'INVALID_PARAMETER' });
  });

  it('serves the deepest page the contract allows', async () => {
    expect((await call('/api/v1/files?page=500&size=20')).status).toBe(200);
  });

  it('searches without regard to accents, as the service does', async () => {
    const response = await call('/api/v1/files?q=securite');
    const page = (await response.json()) as { content: { filename: string }[] };

    expect(page.content.map((file) => file.filename)).toContain('check-list-sécurité.pdf');
  });

  it('422 when an idempotency key comes back with another file', async () => {
    expect((await upload('a.txt', 'first', 'key-1')).status).toBe(202);
    expect((await upload('a.txt', 'first', 'key-1')).status).toBe(202);

    const reused = await upload('b.txt', 'other content', 'key-1');

    expect(reused.status).toBe(422);
    expect(await reused.json()).toMatchObject({ code: 'IDEMPOTENCY_KEY_REUSED' });
  });
});
