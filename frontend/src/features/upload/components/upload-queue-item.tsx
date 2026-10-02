import { cn } from 'cn';
import {
  CheckIcon,
  CircleSlashIcon,
  ClockIcon,
  LoaderIcon,
  RotateCwIcon,
  TriangleAlertIcon,
  XIcon,
} from 'lucide-react';

import { FileTypeIcon } from '@/components/file-type-icon';
import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import { errorMessages, labels } from '@/i18n/messages';
import { formatBytes, formatPercent } from '@/lib/format';

import { isActiveUpload, type UploadItem, type UploadStatus, uploadRatio } from '../lib/upload-queue-store';

interface UploadQueueItemProps {
  item: UploadItem;
  onCancel: (id: string) => void;
  onRetry: (id: string) => void;
  onDismiss: (id: string) => void;
}

type Tone = 'muted' | 'progress' | 'success' | 'error';

function statusLine(item: UploadItem): { text: string; tone: Tone } {
  switch (item.status) {
    case 'queued':
      return { text: labels.upload.queued, tone: 'muted' };
    case 'uploading':
      return {
        text: `${labels.upload.uploading} ${formatPercent(uploadRatio(item))} · ${formatBytes(item.loadedBytes)} / ${formatBytes(item.file.size)}`,
        tone: 'progress',
      };
    case 'succeeded':
      return { text: labels.upload.succeeded, tone: 'success' };
    case 'canceled':
      return { text: labels.upload.canceled, tone: 'muted' };
    case 'failed':
    case 'rejected':
      return { text: errorMessages[item.failure?.reason ?? 'UNKNOWN'], tone: 'error' };
  }
}

const MARKERS: Record<UploadStatus, { icon: typeof CheckIcon; className: string }> = {
  queued: { icon: ClockIcon, className: 'bg-muted-foreground' },
  uploading: { icon: LoaderIcon, className: 'bg-status-scanning [&>svg]:animate-spin' },
  succeeded: { icon: CheckIcon, className: 'bg-status-available' },
  failed: { icon: TriangleAlertIcon, className: 'bg-status-warning' },
  rejected: { icon: TriangleAlertIcon, className: 'bg-status-warning' },
  canceled: { icon: CircleSlashIcon, className: 'bg-muted-foreground' },
};

const TONE_CLASS: Record<Tone, string> = {
  muted: 'text-muted-foreground',
  progress: 'text-status-scanning tabular-nums',
  success: 'text-status-available',
  error: 'text-status-warning',
};

export function UploadQueueItem({ item, onCancel, onRetry, onDismiss }: UploadQueueItemProps) {
  const line = statusLine(item);
  const isActive = isActiveUpload(item);
  const canRetry = item.status === 'canceled' || (item.status === 'failed' && item.failure?.retryable === true);
  const name = item.file.name;
  const marker = MARKERS[item.status];
  const MarkerIcon = marker.icon;

  return (
    <li className="flex items-center gap-3 py-2.5">
      <span className="relative">
        <FileTypeIcon filename={name} size="sm" />
        <span
          aria-hidden
          className={cn(
            'absolute -right-1 -bottom-1 flex size-4 items-center justify-center rounded-full text-solid-foreground ring-2 ring-card [&>svg]:size-2.5',
            marker.className,
          )}
        >
          <MarkerIcon />
        </span>
      </span>
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex items-baseline justify-between gap-3">
          {/* A file name is hostile data: rendered as plain text only (rule F-9). */}
          <p className="truncate text-sm font-medium" title={name}>
            {name}
          </p>
          <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{formatBytes(item.file.size)}</span>
        </div>
        {item.status === 'uploading' && (
          <Progress
            value={uploadRatio(item) * 100}
            getAriaValueText={() => formatPercent(uploadRatio(item))}
            aria-label={`${labels.upload.uploading} ${name}`}
            className="**:data-[slot=progress-indicator]:rounded-full **:data-[slot=progress-indicator]:bg-[linear-gradient(90deg,var(--ring),var(--brand-sky))] *:data-[slot=progress-track]:h-1.5"
          />
        )}
        <p className={cn('text-xs', TONE_CLASS[line.tone])}>{line.text}</p>
      </div>
      <div className="flex shrink-0 gap-0.5">
        {canRetry && (
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={() => onRetry(item.id)}
            aria-label={`${labels.retry} : ${name}`}
          >
            <RotateCwIcon />
          </Button>
        )}
        <Button
          variant="ghost"
          size="icon-sm"
          onClick={() => (isActive ? onCancel(item.id) : onDismiss(item.id))}
          aria-label={`${isActive ? labels.cancel : labels.dismiss} : ${name}`}
        >
          <XIcon />
        </Button>
      </div>
    </li>
  );
}
