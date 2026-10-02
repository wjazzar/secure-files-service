import { ApiError, type ErrorReason } from '@/api/api-error';
import type { FileDetail } from '@/api/files/files-schemas';

import { createFileSelectionSchema, validateSelection } from './validate-selection';

export type UploadStatus = 'queued' | 'uploading' | 'succeeded' | 'failed' | 'canceled' | 'rejected';

export interface UploadFailure {
  reason: ErrorReason;
  /** Transient failure: offering "Retry" makes sense. */
  retryable: boolean;
}

export interface UploadItem {
  readonly id: string;
  readonly file: File;
  readonly status: UploadStatus;
  /** Bytes sent so far. */
  readonly loadedBytes: number;
  /** Kept for every attempt of this file, so a retry never creates a duplicate (rule F-6). */
  readonly idempotencyKey: string;
  readonly failure: UploadFailure | null;
  readonly result: FileDetail | null;
}

export type UploadTransport = (
  file: File,
  options: { idempotencyKey: string; signal: AbortSignal; onProgress: (loadedBytes: number) => void },
) => Promise<FileDetail>;

export interface UploadQueueOptions {
  transport: UploadTransport;
  maxFileSizeBytes: number;
  /** Replaced by the tests, to get stable identifiers. */
  generateId?: () => string;
}

type Listener = () => void;
type UploadedListener = (file: FileDetail) => void;

/**
 * The browser keeps ~6 connections per origin, shared with polling and
 * downloads: 3 simultaneous uploads leave room for the rest of the page.
 */
const MAX_CONCURRENT = 3;

const ACTIVE: ReadonlySet<UploadStatus> = new Set(['queued', 'uploading']);

/** Waiting for its turn or being sent: it can be canceled, not dismissed. */
export function isActiveUpload(item: Pick<UploadItem, 'status'>): boolean {
  return ACTIVE.has(item.status);
}

/** Share of the file already sent, from 0 to 1. */
export function uploadRatio(item: Pick<UploadItem, 'file' | 'loadedBytes'>): number {
  return item.file.size > 0 ? item.loadedBytes / item.file.size : 0;
}

/**
 * Upload queue, written without React so that it can be tested on its own.
 *
 * `File` objects and `AbortController`s never go through React state: React
 * reads immutable snapshots through `subscribe` / `getSnapshot`
 * (`useSyncExternalStore`).
 */
export class UploadQueueStore {
  readonly #transport: UploadTransport;
  readonly #generateId: () => string;
  readonly #selectionSchema: ReturnType<typeof createFileSelectionSchema>;
  readonly #controllers = new Map<string, AbortController>();
  readonly #listeners = new Set<Listener>();
  readonly #uploadedListeners = new Set<UploadedListener>();
  #items: readonly UploadItem[] = [];

  constructor(options: UploadQueueOptions) {
    this.#transport = options.transport;
    this.#generateId = options.generateId ?? (() => crypto.randomUUID());
    this.#selectionSchema = createFileSelectionSchema(options.maxFileSizeBytes);
  }

  subscribe = (listener: Listener): (() => void) => {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  };

  getSnapshot = (): readonly UploadItem[] => this.#items;

  /** Notified once per successful upload (e.g. to refresh the file list). */
  onUploaded(listener: UploadedListener): () => void {
    this.#uploadedListeners.add(listener);
    return () => this.#uploadedListeners.delete(listener);
  }

  add(files: Iterable<File>): void {
    const added = Array.from(files, (file): UploadItem => {
      const rejection = validateSelection(this.#selectionSchema, file);
      return {
        id: this.#generateId(),
        file,
        status: rejection ? 'rejected' : 'queued',
        loadedBytes: 0,
        idempotencyKey: this.#generateId(),
        failure: rejection ? { reason: rejection, retryable: false } : null,
        result: null,
      };
    });
    if (added.length === 0) return;
    this.#setItems([...this.#items, ...added]);
    this.#pump();
  }

  cancel(id: string): void {
    const item = this.#find(id);
    if (!item || !isActiveUpload(item)) return;
    this.#controllers.get(id)?.abort();
    this.#update(id, { status: 'canceled', loadedBytes: 0 });
    this.#pump();
  }

  /** Same file, same idempotency key: the server returns the first result if it already has it. */
  retry(id: string): void {
    const item = this.#find(id);
    if (!item || (item.status !== 'failed' && item.status !== 'canceled')) return;
    this.#update(id, { status: 'queued', loadedBytes: 0, failure: null });
    this.#pump();
  }

  dismiss(id: string): void {
    const item = this.#find(id);
    if (!item || isActiveUpload(item)) return;
    this.#setItems(this.#items.filter((candidate) => candidate.id !== id));
  }

  /** Aborts every running upload (the page that owns the queue is going away). */
  dispose(): void {
    this.#controllers.forEach((controller) => controller.abort());
  }

  clearFinished(): void {
    this.#setItems(this.#items.filter(isActiveUpload));
  }

  #pump(): void {
    let running = this.#items.filter((item) => item.status === 'uploading').length;
    for (const item of this.#items) {
      if (running >= MAX_CONCURRENT) return;
      if (item.status !== 'queued') continue;
      running += 1;
      void this.#start(item);
    }
  }

  async #start(item: UploadItem): Promise<void> {
    const controller = new AbortController();
    this.#controllers.set(item.id, controller);
    this.#update(item.id, { status: 'uploading', loadedBytes: 0 });
    try {
      const result = await this.#transport(item.file, {
        idempotencyKey: item.idempotencyKey,
        signal: controller.signal,
        onProgress: (loadedBytes) => {
          if (this.#find(item.id)?.status === 'uploading') this.#update(item.id, { loadedBytes });
        },
      });
      if (controller.signal.aborted) return;
      this.#update(item.id, { status: 'succeeded', loadedBytes: item.file.size, result });
      this.#uploadedListeners.forEach((listener) => listener(result));
    } catch (error) {
      if (controller.signal.aborted) return; // canceled by the user: already recorded
      const apiError = error instanceof ApiError ? error : new ApiError({ kind: 'network', cause: error });
      this.#update(item.id, {
        status: 'failed',
        failure: { reason: apiError.reason, retryable: apiError.isRetryable },
      });
    } finally {
      this.#controllers.delete(item.id);
      this.#pump();
    }
  }

  #find(id: string): UploadItem | undefined {
    return this.#items.find((item) => item.id === id);
  }

  #update(id: string, patch: Partial<Omit<UploadItem, 'id' | 'file' | 'idempotencyKey'>>): void {
    this.#setItems(this.#items.map((item) => (item.id === id ? { ...item, ...patch } : item)));
  }

  #setItems(items: readonly UploadItem[]): void {
    this.#items = items;
    this.#listeners.forEach((listener) => listener());
  }
}
