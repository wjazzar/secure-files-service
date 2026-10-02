import { ApiError } from '@/api/api-error';
import type { FileDetail } from '@/api/files/files-schemas';

import { isActiveUpload, type UploadTransport, UploadQueueStore, uploadRatio } from './upload-queue-store';

interface Call {
  file: File;
  idempotencyKey: string;
  signal: AbortSignal;
  onProgress: (loaded: number) => void;
  resolve: (detail: FileDetail) => void;
  reject: (error: unknown) => void;
}

/** Transport whose calls are resolved by hand, to observe the queue step by step. */
function manualTransport() {
  const calls: Call[] = [];
  const transport: UploadTransport = (file, options) =>
    new Promise<FileDetail>((resolve, reject) => {
      calls.push({ file, ...options, resolve, reject });
    });
  return { calls, transport };
}

const fileDetail = (name: string) => ({ id: name, filename: name }) as unknown as FileDetail;
const file = (name: string, size = 10) => new File(['x'.repeat(size)], name);
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function createStore(transport: UploadTransport) {
  let counter = 0;
  return new UploadQueueStore({
    transport,
    maxFileSizeBytes: 100,
    generateId: () => `id-${++counter}`,
  });
}

describe('UploadQueueStore', () => {
  it('uploads at most 3 files at a time, the next ones wait their turn', async () => {
    const { calls, transport } = manualTransport();
    const store = createStore(transport);

    store.add([file('a'), file('b'), file('c'), file('d')]);

    expect(calls).toHaveLength(3);
    expect(store.getSnapshot().map((item) => item.status)).toEqual(['uploading', 'uploading', 'uploading', 'queued']);

    calls[0]?.resolve(fileDetail('a'));
    await flush();

    expect(calls).toHaveLength(4);
    expect(store.getSnapshot()[0]?.status).toBe('succeeded');
  });

  it('rejects empty and oversized files before sending anything (rule F-8)', () => {
    const { calls, transport } = manualTransport();
    const store = createStore(transport);

    store.add([file('empty', 0), file('big', 101)]);

    expect(calls).toHaveLength(0);
    expect(store.getSnapshot().map((item) => [item.status, item.failure?.reason])).toEqual([
      ['rejected', 'EMPTY_FILE'],
      ['rejected', 'FILE_TOO_LARGE'],
    ]);
  });

  it('retries a failed upload with the SAME idempotency key (rule F-6)', async () => {
    const { calls, transport } = manualTransport();
    const store = createStore(transport);
    store.add([file('a')]);
    const firstKey = calls[0]?.idempotencyKey;

    calls[0]?.reject(new ApiError({ kind: 'network' }));
    await flush();
    expect(store.getSnapshot()[0]).toMatchObject({
      status: 'failed',
      failure: { reason: 'NETWORK_ERROR', retryable: true },
    });

    store.retry('id-1');

    expect(calls).toHaveLength(2);
    expect(calls[1]?.idempotencyKey).toBe(firstKey);
  });

  it('does not offer a retry for a refusal that would only be repeated', async () => {
    const { calls, transport } = manualTransport();
    const store = createStore(transport);
    store.add([file('a')]);

    calls[0]?.reject(new ApiError({ kind: 'http', status: 400, code: 'INVALID_FILE_NAME' }));
    await flush();

    expect(store.getSnapshot()[0]?.failure).toEqual({ reason: 'INVALID_FILE_NAME', retryable: false });
  });

  it('cancels an upload by aborting its request, and starts the next one', () => {
    const { calls, transport } = manualTransport();
    const store = createStore(transport);
    store.add([file('a'), file('b'), file('c'), file('d')]);

    store.cancel('id-1');

    expect(calls[0]?.signal.aborted).toBe(true);
    expect(store.getSnapshot()[0]?.status).toBe('canceled');
    expect(calls).toHaveLength(4);
  });

  it('reports progress and notifies each successful upload', async () => {
    const { calls, transport } = manualTransport();
    const store = createStore(transport);
    const uploaded = vi.fn();
    store.onUploaded(uploaded);
    store.add([file('a', 50)]);

    calls[0]?.onProgress(20);
    expect(store.getSnapshot()[0]?.loadedBytes).toBe(20);

    calls[0]?.resolve(fileDetail('a'));
    await flush();
    expect(uploaded).toHaveBeenCalledWith(fileDetail('a'));
  });
});

describe('upload item helpers', () => {
  it('treats a waiting or running upload as active, and nothing else', () => {
    expect(isActiveUpload({ status: 'queued' })).toBe(true);
    expect(isActiveUpload({ status: 'uploading' })).toBe(true);
    for (const status of ['succeeded', 'failed', 'canceled', 'rejected'] as const) {
      expect(isActiveUpload({ status })).toBe(false);
    }
  });

  it('reports the share already sent, and zero for an empty file instead of dividing by it', () => {
    expect(uploadRatio({ file: file('a', 40), loadedBytes: 10 })).toBe(0.25);
    expect(uploadRatio({ file: file('empty', 0), loadedBytes: 0 })).toBe(0);
  });
});
