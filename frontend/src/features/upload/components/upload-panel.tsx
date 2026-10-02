import { uploadFile } from '@/api/files/files-api';
import type { FileDetail } from '@/api/files/files-schemas';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { env } from '@/config/env';
import { labels } from '@/i18n/messages';

import { useUploadQueue } from '../hooks/use-upload-queue';
import { isActiveUpload } from '../lib/upload-queue-store';
import { UploadDropzone } from './upload-dropzone';
import { UploadQueueItem } from './upload-queue-item';

interface UploadPanelProps {
  /** Called after each successful upload. The feature knows nothing of the file list. */
  onUploaded?: (file: FileDetail) => void;
}

export function UploadPanel({ onUploaded }: UploadPanelProps) {
  const queue = useUploadQueue({
    transport: uploadFile,
    maxFileSizeBytes: env.VITE_MAX_UPLOAD_BYTES,
    onUploaded,
  });
  const hasFinished = queue.items.some((item) => !isActiveUpload(item));

  return (
    <Card className="workspace-card workspace-upload-card">
      <CardHeader>
        <CardTitle>
          <h2 className="workspace-card-title">{labels.upload.title}</h2>
        </CardTitle>
        <CardDescription className="workspace-card-description">{labels.upload.subtitle}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <UploadDropzone onFilesSelected={queue.add} />
        {queue.items.length > 0 && (
          <section aria-label={labels.upload.queueTitle} className="rounded-lg border bg-muted/30">
            <div className="flex items-center justify-between border-b px-3 py-2">
              <h3 className="text-sm font-semibold whitespace-nowrap">
                {labels.upload.queueTitle}
                <span className="ml-1.5 font-normal text-muted-foreground tabular-nums">({queue.items.length})</span>
              </h3>
              {hasFinished && (
                <Button variant="link" size="sm" className="h-auto p-0" onClick={queue.clearFinished}>
                  {labels.upload.clearFinished}
                </Button>
              )}
            </div>
            <ul className="max-h-80 divide-y divide-border overflow-y-auto px-3">
              {queue.items.map((item) => (
                <UploadQueueItem
                  key={item.id}
                  item={item}
                  onCancel={queue.cancel}
                  onRetry={queue.retry}
                  onDismiss={queue.dismiss}
                />
              ))}
            </ul>
          </section>
        )}
      </CardContent>
    </Card>
  );
}
