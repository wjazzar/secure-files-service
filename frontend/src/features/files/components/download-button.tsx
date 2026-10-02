import { DownloadIcon, LoaderIcon } from 'lucide-react';

import type { FileSummary } from '@/api/files/files-schemas';
import { Button } from '@/components/ui/button';
import { labels } from '@/i18n/messages';

import { useDownloadFile } from '../hooks/use-download-file';

interface DownloadButtonProps {
  file: Pick<FileSummary, 'id' | 'filename' | 'downloadable'>;
  /** `icon` in table rows, `full` in the detail panel. */
  variant?: 'icon' | 'full';
}

/**
 * Exists only when the server says the file is downloadable (rule F-1): the
 * status is never consulted here.
 */
export function DownloadButton({ file, variant = 'full' }: DownloadButtonProps) {
  const download = useDownloadFile();
  if (!file.downloadable) return null;

  const pending = download.isPending;
  const onClick = () => download.mutate({ fileId: file.id, filename: file.filename });
  const Icon = pending ? LoaderIcon : DownloadIcon;
  const iconClass = pending ? 'animate-spin' : undefined;

  if (variant === 'icon') {
    return (
      <Button
        variant="ghost"
        size="icon-sm"
        className="workspace-download-action"
        onClick={onClick}
        disabled={pending}
        aria-label={`${labels.download} ${file.filename}`}
        title={labels.download}
      >
        <Icon className={iconClass} />
      </Button>
    );
  }
  return (
    <Button className="workspace-primary-action" onClick={onClick} disabled={pending}>
      <Icon data-icon="inline-start" className={iconClass} />
      {pending ? labels.downloading : labels.download}
    </Button>
  );
}
