import { useEffect, useState, useSyncExternalStore } from 'react';

import type { FileDetail } from '@/api/files/files-schemas';

import { isActiveUpload, UploadQueueStore, type UploadQueueOptions } from '../lib/upload-queue-store';

/**
 * Creates an upload queue for the lifetime of the calling component and
 * exposes its snapshot to React.
 */
export function useUploadQueue(options: UploadQueueOptions & { onUploaded?: (file: FileDetail) => void }) {
  const [store] = useState(() => new UploadQueueStore(options));
  const items = useSyncExternalStore(store.subscribe, store.getSnapshot);
  const { onUploaded } = options;

  useEffect(() => (onUploaded ? store.onUploaded(onUploaded) : undefined), [store, onUploaded]);

  // Signing out (or leaving the page in the application) stops running uploads.
  useEffect(() => () => store.dispose(), [store]);

  // Leaving the page aborts running uploads: ask for confirmation first.
  const hasActiveUploads = items.some(isActiveUpload);
  useEffect(() => {
    if (!hasActiveUploads) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [hasActiveUploads]);

  return {
    items,
    add: (files: Iterable<File>) => store.add(files),
    cancel: (id: string) => store.cancel(id),
    retry: (id: string) => store.retry(id),
    dismiss: (id: string) => store.dismiss(id),
    clearFinished: () => store.clearFinished(),
  };
}
