import { isContentLink, isNavigableUrl } from './safe-url';

describe('isContentLink', () => {
  it('accepts the content path the service returns, relative or absolute on this origin', () => {
    expect(isContentLink('/api/v1/files/0b7f5f0e-1c2d-4e3f-8a9b-0c1d2e3f4a5b/content')).toBe(true);
    expect(isContentLink(`${window.location.origin}/api/v1/files/abc/content`)).toBe(true);
  });

  it("accepts the mock API's in-page blob: URL of this origin", () => {
    expect(isContentLink(`blob:${window.location.origin}/7c1e4a52-9a1b-4c55-9d2e-2f1a8e6b3c4d`)).toBe(true);
  });

  it.each([
    ['a script', 'javascript:alert(document.cookie)'],
    ['inline data', 'data:text/html,<script>alert(1)</script>'],
    ['another site', 'https://evil.example/api/v1/files/abc/content'],
    ['another site, protocol-relative', '//evil.example/api/v1/files/abc/content'],
    ['another path of this origin', '/api/v1/auth/logout'],
    ['a path that climbs out', '/api/v1/files/../auth/logout'],
    ['a foreign blob', 'blob:https://evil.example/7c1e4a52'],
  ])('refuses %s', (_, value) => {
    expect(isContentLink(value)).toBe(false);
  });
});

describe('isNavigableUrl', () => {
  it("accepts Keycloak's sign-out address", () => {
    expect(isNavigableUrl('http://localhost:8081/realms/praxedo/protocol/openid-connect/logout?id_token_hint=x')).toBe(
      true,
    );
    expect(isNavigableUrl('https://sso.example/realms/praxedo/protocol/openid-connect/logout')).toBe(true);
  });

  it.each([
    ['a script', 'javascript:alert(1)'],
    ['inline data', 'data:text/html,<h1>hi</h1>'],
    ['a file', 'file:///etc/passwd'],
  ])('refuses %s', (_, value) => {
    expect(isNavigableUrl(value)).toBe(false);
  });
});
