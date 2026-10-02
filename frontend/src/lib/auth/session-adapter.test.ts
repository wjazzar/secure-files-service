import { http, HttpResponse } from 'msw';

import { server } from '@/testing/mocks/server';

import { LogoutError } from './auth-adapter';
import { createSessionAdapter } from './session-adapter';

const ALICE = { id: 'alice-sub', username: 'alice', displayName: 'Alice Demo', email: 'alice@example.test' };

function clearCookies() {
  for (const cookie of document.cookie.split('; ')) {
    const name = cookie.split('=')[0];
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  }
}

beforeEach(clearCookies);

describe('session adapter (v2, cookie only)', () => {
  it('restores the session the cookie belongs to', async () => {
    server.use(http.get('*/api/v1/auth/session', () => HttpResponse.json(ALICE)));
    const adapter = createSessionAdapter();

    await adapter.init();

    expect(adapter.getSnapshot()).toEqual({
      status: 'authenticated',
      user: { id: 'alice-sub', username: 'alice', name: 'Alice Demo', email: 'alice@example.test' },
      reason: null,
    });
  });

  it('stays signed out on 401, on a network failure, and on a response that does not match the contract', async () => {
    for (const answer of [
      () => HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 }),
      () => HttpResponse.error(),
      () => HttpResponse.json({ id: 42 }),
    ]) {
      server.use(http.get('*/api/v1/auth/session', answer));
      const adapter = createSessionAdapter();
      await adapter.init();
      expect(adapter.getSnapshot().status).toBe('unauthenticated');
    }
  });

  it('never holds a token: the browser only has an HttpOnly cookie it cannot read', async () => {
    server.use(http.get('*/api/v1/auth/session', () => HttpResponse.json(ALICE)));
    const adapter = createSessionAdapter();
    await adapter.init();

    expect(await adapter.getAccessToken()).toBeNull();
    expect(JSON.stringify(sessionStorage)).toBe('{}');
    expect(JSON.stringify(localStorage)).toBe('{}');
  });

  it('signs in by leaving for the service, which sends the browser to Keycloak and back to where it was going', async () => {
    const navigate = vi.fn();
    const adapter = createSessionAdapter(navigate);

    await adapter.login(undefined, '/files/abc?status=AVAILABLE');

    expect(navigate).toHaveBeenCalledWith('/api/v1/auth/login?redirect=%2Ffiles%2Fabc%3Fstatus%3DAVAILABLE');
  });

  it('never forwards a foreign return target', async () => {
    const navigate = vi.fn();
    const adapter = createSessionAdapter(navigate);

    await adapter.login(undefined, '//evil.example/phish');

    expect(navigate).toHaveBeenCalledWith('/api/v1/auth/login?redirect=%2F');
  });

  it('signs out through the service, echoing the CSRF token, then leaves for Keycloak to end its session too', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-123; path=/';
    const endSession =
      'http://localhost:8081/realms/praxedo/protocol/openid-connect/logout?id_token_hint=x&post_logout_redirect_uri=y';
    const logout = vi.fn();
    server.use(
      http.get('*/api/v1/auth/session', () => HttpResponse.json(ALICE)),
      http.post('*/api/v1/auth/logout', ({ request }) => {
        logout(request.headers.get('X-XSRF-TOKEN'));
        return HttpResponse.json({ logoutUrl: endSession });
      }),
    );
    const navigate = vi.fn();
    const adapter = createSessionAdapter(navigate);
    await adapter.init();

    await adapter.logout();

    expect(logout).toHaveBeenCalledWith('csrf-123');
    expect(adapter.getSnapshot()).toMatchObject({ status: 'unauthenticated', reason: 'signed-out' });
    expect(navigate).toHaveBeenCalledWith(endSession);
  });

  it.each([
    ['the service cannot be reached', () => HttpResponse.error()],
    ['the CSRF token is refused', () => HttpResponse.json({ code: 'CSRF_TOKEN_INVALID' }, { status: 403 })],
    ['the service fails', () => HttpResponse.json({ code: 'INTERNAL_ERROR' }, { status: 500 })],
  ])('stays signed in, and says so, when %s: the session is still open at the service', async (_, answer) => {
    server.use(
      http.get('*/api/v1/auth/session', () => HttpResponse.json(ALICE)),
      http.post('*/api/v1/auth/logout', answer),
    );
    const navigate = vi.fn();
    const adapter = createSessionAdapter(navigate);
    await adapter.init();

    await expect(adapter.logout()).rejects.toBeInstanceOf(LogoutError);

    expect(adapter.getSnapshot().status).toBe('authenticated');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('is signed out when the service had no session left to close (401)', async () => {
    server.use(
      http.get('*/api/v1/auth/session', () => HttpResponse.json(ALICE)),
      http.post('*/api/v1/auth/logout', () => HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 })),
    );
    const navigate = vi.fn();
    const adapter = createSessionAdapter(navigate);
    await adapter.init();

    await adapter.logout();

    expect(adapter.getSnapshot()).toMatchObject({ status: 'unauthenticated', reason: 'signed-out' });
    expect(navigate).not.toHaveBeenCalled();
  });

  it('marks the session as expired when the API answers 401', async () => {
    server.use(http.get('*/api/v1/auth/session', () => HttpResponse.json(ALICE)));
    const adapter = createSessionAdapter();
    await adapter.init();

    adapter.onUnauthorized();

    expect(adapter.getSnapshot()).toMatchObject({ status: 'unauthenticated', reason: 'expired' });
  });
});
