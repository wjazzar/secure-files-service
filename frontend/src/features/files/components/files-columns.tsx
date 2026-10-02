import { createColumnHelper } from '@tanstack/react-table';

import type { FileSummary } from '@/api/files/files-schemas';
import { labels } from '@/i18n/messages';
import { formatBytes, formatDateTime } from '@/lib/format';

import { filesTableFeatures } from '../lib/files-table-features';
import { DownloadButton } from './download-button';
import { FileNameLink } from './file-name-link';
import { StatusBadge } from './status-badge';

const helper = createColumnHelper<typeof filesTableFeatures, FileSummary>();

/**
 * Column ids match the API sort fields (`uploadedAt`, `filename`,
 * `sizeBytes`): the sorting state converts to `sort=field,direction` as is.
 */
export const filesColumns = helper.columns([
  helper.accessor('filename', {
    header: labels.files.columns.filename,
    cell: (info) => <FileNameLink file={info.row.original} />,
    meta: { grow: true },
    sortDescFirst: false,
  }),
  helper.accessor('sizeBytes', {
    header: labels.files.columns.size,
    cell: (info) => <span className="tabular-nums">{formatBytes(info.getValue())}</span>,
    meta: { align: 'end' },
  }),
  helper.accessor('status', {
    header: labels.files.columns.status,
    cell: (info) => <StatusBadge status={info.getValue()} className="workspace-table-badge" />,
    enableSorting: false,
  }),
  helper.accessor('uploadedAt', {
    header: labels.files.columns.uploadedAt,
    cell: (info) => (
      <time dateTime={info.getValue()} className="whitespace-nowrap text-muted-foreground tabular-nums">
        {formatDateTime(info.getValue())}
      </time>
    ),
  }),
  helper.display({
    id: 'actions',
    header: () => <span className="sr-only">{labels.files.columns.actions}</span>,
    // Above the row-wide link overlay, so that it stays clickable on its own.
    cell: (info) => (
      <div className="relative z-10 inline-flex">
        <DownloadButton file={info.row.original} variant="icon" />
      </div>
    ),
    meta: { align: 'end' },
  }),
]);
