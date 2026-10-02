import { createMockDb } from '@/testing/mocks/db';
import { createHandlers } from '@/testing/mocks/handlers';
import { server } from '@/testing/mocks/server';

import { LoginError } from './auth-adapter';
import { createMockAdapter } from './mock-adapter';

function setup() {
  const db = createMockDb();
  server.use(...createHandlers(db, { latencyMs: 0 }));
  return { db, adapter: createMockAdapter() };
}

beforeEach(() => sessionStorage.clear());

describe('mock authentication adapter', () => {
  it('signs in with valid credentials and keeps the token in memory', async () => {
    const { db, adapter } = setup();

    await adapter.login({ username: 'Alice ', password: 'demo' });

    expect(adapter.getSnapshot()).toMatchObject({
      status: 'authenticated',
      user: { username: 'alice', name: 'Alice Martin' },
    });
    const token = await adapter.getAccessToken();
    expect(token && db.tokens.get(token)).toBe('u-alice');
    expect(JSON.stringify(localStorage)).not.toContain(token);
  });

  it('refuses a wrong password with the same error as an unknown user', async () => {
    const { adapter } = setup();

    const wrongPassword = await adapter.login({ username: 'alice', password: 'nope' }).catch((e: unknown) => e);
    const unknownUser = await adapter.login({ username: 'mallory', password: 'demo' }).catch((e: unknown) => e);

    expect(wrongPassword).toEqual(new LoginError('INVALID_CREDENTIALS'));
    expect(unknownUser).toEqual(new LoginError('INVALID_CREDENTIALS'));
    expect(adapter.getSnapshot().status).toBe('unauthenticated');
  });

  it('locks the account after 5 failed attempts', async () => {
    const { adapter } = setup();
    for (let attempt = 0; attempt < 5; attempt++) {
      await adapter.login({ username: 'bob', password: 'wrong' }).catch(() => undefined);
    }

    const error = await adapter.login({ username: 'bob', password: 'demo' }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(LoginError);
    expect(error).toMatchObject({ code: 'TOO_MANY_ATTEMPTS' });
    expect((error as LoginError).retryAfterSeconds).toBeGreaterThan(0);
  });

  it('restores the session after a reload, with a new token', async () => {
    const { adapter } = setup();
    await adapter.login({ username: 'claire', password: 'demo' });
    const firstToken = await adapter.getAccessToken();

    const reloaded = createMockAdapter();
    await reloaded.init();

    expect(reloaded.getSnapshot().user?.username).toBe('claire');
    expect(await reloaded.getAccessToken()).not.toBe(firstToken);
  });

  it('signs out, revokes the tokens and ends the identity provider session', async () => {
    const { db, adapter } = setup();
    await adapter.login({ username: 'bob', password: 'demo' });

    await adapter.logout();

    expect(adapter.getSnapshot()).toMatchObject({ status: 'unauthenticated', reason: 'signed-out' });
    expect(await adapter.getAccessToken()).toBeNull();
    expect([...db.tokens.values()]).not.toContain('u-bob');
    const reloaded = createMockAdapter();
    await reloaded.init();
    expect(reloaded.getSnapshot().status).toBe('unauthenticated');
  });

  it('marks the session as expired when the API answers 401', async () => {
    const { adapter } = setup();
    await adapter.login({ username: 'alice', password: 'demo' });

    adapter.onUnauthorized();

    expect(adapter.getSnapshot()).toMatchObject({ status: 'unauthenticated', reason: 'expired' });
  });
});
