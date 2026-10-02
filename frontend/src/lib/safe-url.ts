/**
 * URLs received from the server are checked before the browser follows them.
 *
 * The server is trusted, so this is defence in depth (audit S-14): were a
 * proxy, a mock or a future bug to hand back `javascript:…` or a foreign
 * address, following it would run code in the application's origin or send
 * the user elsewhere. Anything that is not what the contract promises is
 * refused at parse time, where every response is already validated (F-15).
 */

/** Where the contract serves file contents. */
const CONTENT_PATH = '/api/v1/files/';

function parse(value: string): URL | null {
  try {
    return new URL(value, window.location.origin);
  } catch {
    return null;
  }
}

/**
 * A file's content link: a path of this origin under `/api/v1/files/`, as the
 * service returns it — or, outside builds, an in-page `blob:` URL of this
 * origin, which is how the mock API stands in for a download (MSW cannot
 * intercept a navigation). A build has no mock API, and refuses `blob:`.
 */
export function isContentLink(value: string): boolean {
  const url = parse(value);
  if (url?.origin !== window.location.origin) return false;
  if (url.protocol === 'blob:') return import.meta.env.DEV;
  return (url.protocol === 'http:' || url.protocol === 'https:') && url.pathname.startsWith(CONTENT_PATH);
}

/** A page the browser may be sent to, such as Keycloak's sign-out: plain `http(s)` only. */
export function isNavigableUrl(value: string): boolean {
  const url = parse(value);
  return url !== null && (url.protocol === 'https:' || url.protocol === 'http:');
}
