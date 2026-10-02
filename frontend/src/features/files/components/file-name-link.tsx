import { Link, useLocation } from 'react-router';

import type { FileSummary } from '@/api/files/files-schemas';
import { FileTypeIcon } from '@/components/file-type-icon';

/**
 * Opens the detail panel, keeping the table state (search, filters, page) in
 * the URL. The name is rendered as plain text (rule F-9).
 */
export function FileNameLink({ file }: { file: Pick<FileSummary, 'id' | 'filename' | 'contentType'> }) {
  const location = useLocation();
  return (
    <div className="flex min-w-0 items-center gap-3">
      <FileTypeIcon filename={file.filename} size="sm" />
      <div className="min-w-0">
        <Link
          to={{ pathname: `/files/${file.id}`, search: location.search }}
          className="workspace-file-name block truncate font-medium text-foreground underline-offset-4 after:absolute after:inset-0 hover:underline focus-visible:underline"
          title={file.filename}
        >
          {file.filename}
        </Link>
        <span className="workspace-file-type block truncate text-muted-foreground">{file.contentType}</span>
      </div>
    </div>
  );
}
