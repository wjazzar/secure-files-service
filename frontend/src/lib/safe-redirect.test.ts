import { safeRedirect } from './safe-redirect';

describe('safeRedirect (open redirect protection)', () => {
  it('keeps a same-site path with its query', () => {
    expect(safeRedirect('/files/abc?status=AVAILABLE')).toBe('/files/abc?status=AVAILABLE');
  });

  it.each([
    ['absolute URL', 'https://evil.example/phishing'],
    ['protocol-relative URL', '//evil.example'],
    ['backslash trick', '/\\evil.example'],
    ['javascript URL', 'javascript:alert(1)'],
    ['login loop', '/login?redirect=/'],
    ['tab before a second slash', '/\t/evil.example'],
    ['line break before a second slash', '/\n/evil.example'],
    ['inner backslash', '/files\\..\\evil'],
    ['space', '/files /x'],
    ['path too long', `/${'a'.repeat(2048)}`],
    ['missing', null],
  ])('falls back to the home page for a %s', (_, value) => {
    expect(safeRedirect(value)).toBe('/');
  });
});
