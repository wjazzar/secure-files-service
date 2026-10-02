/** Longest return path kept — the service applies the same bound (ReturnTo.java). */
const MAX_LENGTH = 2048;

/** Backslash, control character (C0, DEL, C1) or white space of any kind. */
function unsafe(character: string): boolean {
  const code = character.codePointAt(0) ?? 0;
  return character === '\\' || code <= 0x1f || (code >= 0x7f && code <= 0x9f) || /\s/u.test(character);
}

/**
 * Target to go back to after signing in, taken from `?redirect=`.
 *
 * Only a same-site path is accepted: `//evil.example` or
 * `https://evil.example` would turn the login page into an open redirect
 * (a trusted link that lands on a phishing site after authentication).
 * Same rule as the service's: no backslash, control character or white space
 * anywhere — `/%09/evil.example` decodes to a path the browser reads as
 * `//evil.example`.
 */
export function safeRedirect(value: string | null | undefined, fallback = '/'): string {
  if (!value?.startsWith('/') || value.length > MAX_LENGTH) return fallback;
  if (value.startsWith('//')) return fallback;
  // Code point by code point: a control character is never part of a longer sequence.
  for (const character of value) if (unsafe(character)) return fallback;
  if (value.startsWith('/login')) return fallback;
  return value;
}
