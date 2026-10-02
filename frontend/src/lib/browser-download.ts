/**
 * Hands a download over to the browser's native download manager.
 *
 * The response is streamed to disk by the browser; nothing goes through
 * JavaScript memory (rule F-4). The server answers with
 * `Content-Disposition: attachment`, so the page is not left. `download`
 * only names the file when the link is same-origin and the server gives no
 * name.
 */
export function startBrowserDownload(url: string, filename: string): void {
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.rel = 'noopener';
  anchor.style.display = 'none';
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
}
