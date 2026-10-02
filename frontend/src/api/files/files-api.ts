import { apiClient, parseResponse } from '../api-client';
import {
  type FileDetail,
  FileDetailSchema,
  type FilePage,
  FilePageSchema,
  type FilesListParams,
  type FilesSummary,
  FilesSummarySchema,
} from './files-schemas';

interface RequestOptions {
  signal?: AbortSignal | undefined;
}

/** `GET /files` — one page of files. */
export async function getFiles(params: FilesListParams, { signal }: RequestOptions = {}): Promise<FilePage> {
  const response = await apiClient.get('/files', {
    params: {
      page: params.page,
      size: params.size,
      sort: params.sort,
      status: params.status.length > 0 ? params.status : undefined,
      q: params.q,
    },
    signal,
  });
  return parseResponse(FilePageSchema, response.data);
}

/** `GET /files/summary` — counters per status, for the same search. */
export async function getFilesSummary(q: string | undefined, { signal }: RequestOptions = {}): Promise<FilesSummary> {
  const response = await apiClient.get('/files/summary', { params: { q }, signal });
  return parseResponse(FilesSummarySchema, response.data);
}

/** `GET /files/{id}` — metadata, status and verdict. */
export async function getFile(fileId: string, { signal }: RequestOptions = {}): Promise<FileDetail> {
  const response = await apiClient.get(`/files/${encodeURIComponent(fileId)}`, { signal });
  return parseResponse(FileDetailSchema, response.data);
}

interface UploadOptions {
  /** Same key for every attempt of the same file (rule F-6). */
  idempotencyKey: string;
  signal?: AbortSignal | undefined;
  onProgress?: ((loadedBytes: number) => void) | undefined;
}

/**
 * `POST /files` — sends the raw file content.
 *
 * The `File` is handed as is to `XMLHttpRequest.send()`: the browser streams
 * it from disk, nothing is loaded in JavaScript memory (rule F-3). The XHR
 * adapter is forced because `fetch` still exposes no upload progress.
 */
export async function uploadFile(file: File, options: UploadOptions): Promise<FileDetail> {
  const response = await apiClient.post('/files', file, {
    adapter: 'xhr',
    // A 500 MB upload legitimately takes minutes; cancellation goes through
    // `signal` (user action) instead of a global timeout.
    timeout: 0,
    signal: options.signal,
    headers: {
      'Content-Type': 'application/octet-stream',
      'X-File-Name': encodeURIComponent(file.name),
      'Idempotency-Key': options.idempotencyKey,
    },
    onUploadProgress: (event) => options.onProgress?.(event.loaded),
  });
  return parseResponse(FileDetailSchema, response.data);
}
