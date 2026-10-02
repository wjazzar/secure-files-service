import { useQuery } from '@tanstack/react-query';

import { fileQueries } from '@/api/files/files-queries';
import { FileTypeIcon } from '@/components/file-type-icon';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { Skeleton } from '@/components/ui/skeleton';
import { describeError, labels } from '@/i18n/messages';
import { formatBytes } from '@/lib/format';

import { useStatusAnnouncements } from '../hooks/use-status-announcements';
import { AnalysisTimeline } from './analysis-timeline';
import { DownloadButton } from './download-button';
import { FileMetadataSection, ScanVerdictSection } from './file-detail-sections';
import { StatusBadge } from './status-badge';
import { VerdictBanner } from './verdict-banner';

interface FileDetailSheetProps {
  fileId: string;
  onClose: () => void;
}

/**
 * Side panel of one file. Follows the file while it is being analysed
 * (polling stops once it is terminal). Modal: focus trapped, Escape closes,
 * focus returns to the table.
 */
export function FileDetailSheet({ fileId, onClose }: FileDetailSheetProps) {
  const query = useQuery(fileQueries.detail(fileId));
  const file = query.data;
  useStatusAnnouncements(file ? [file] : undefined);

  return (
    <Sheet open onOpenChange={(open) => !open && onClose()}>
      <SheetContent
        className="workspace-detail gap-0 bg-background data-[side=right]:w-full data-[side=right]:sm:max-w-lg"
        aria-busy={query.isPending}
      >
        {file ? (
          <>
            <SheetHeader className="workspace-detail-header gap-3 border-b p-5 pr-12">
              <div className="flex items-start gap-3">
                <FileTypeIcon filename={file.filename} size="lg" />
                <div className="min-w-0 space-y-1.5">
                  {/* Plain text only: a file name is hostile data (rule F-9). */}
                  <SheetTitle className="workspace-detail-title text-lg leading-snug break-all">
                    {file.filename}
                  </SheetTitle>
                  <SheetDescription render={<div />} className="flex flex-wrap items-center gap-2">
                    <StatusBadge status={file.status} />
                    <span className="text-xs text-muted-foreground tabular-nums">
                      {formatBytes(file.sizeBytes)} · {file.contentType}
                    </span>
                  </SheetDescription>
                </div>
              </div>
            </SheetHeader>
            <div className="flex-1 space-y-6 overflow-y-auto p-5">
              <VerdictBanner file={file} />
              <AnalysisTimeline file={file} />
              <ScanVerdictSection file={file} />
              <FileMetadataSection file={file} />
            </div>
            {file.downloadable && (
              <SheetFooter className="workspace-detail-footer border-t bg-card [&_button]:w-full">
                <DownloadButton file={file} />
              </SheetFooter>
            )}
          </>
        ) : query.isError ? (
          <>
            <SheetHeader className="workspace-detail-header p-5 pr-12">
              <SheetTitle className="workspace-detail-title">{labels.details}</SheetTitle>
            </SheetHeader>
            <div className="px-5">
              <Alert>
                <AlertDescription>{describeError(query.error)}</AlertDescription>
              </Alert>
            </div>
          </>
        ) : (
          <>
            <SheetHeader className="workspace-detail-header border-b p-5 pr-12">
              <SheetTitle className="workspace-detail-title flex items-center gap-3">
                <span className="sr-only">{labels.detail.loading}</span>
                <Skeleton className="size-12 rounded-md" />
                <Skeleton className="h-6 w-56" />
              </SheetTitle>
            </SheetHeader>
            <div className="space-y-4 p-5">
              <Skeleton className="h-20 w-full rounded-xl" />
              <Skeleton className="h-28 w-full" />
              <Skeleton className="h-40 w-full" />
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}
