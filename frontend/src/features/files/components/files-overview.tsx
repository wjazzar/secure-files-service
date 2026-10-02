import { useQuery } from '@tanstack/react-query';
import { FilterXIcon, FolderOpenIcon, SearchXIcon } from 'lucide-react';
import { useCallback, useEffect } from 'react';

import { fileQueries } from '@/api/files/files-queries';
import { Alert, AlertAction, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { describeError, labels } from '@/i18n/messages';

import { useFilesSearchParams } from '../hooks/use-files-search-params';
import { useStatusAnnouncements } from '../hooks/use-status-announcements';
import { toListParams, toPageSize } from '../lib/files-search';
import { FilesSearchInput } from './files-search-input';
import { FilesTable } from './files-table';
import { StatusTabs } from './status-tabs';

function EmptyState({ filtered, onReset }: { filtered: boolean; onReset: () => void }) {
  const Icon = filtered ? SearchXIcon : FolderOpenIcon;
  return (
    <div className="flex flex-col items-center gap-2 text-center">
      <span className="mb-2 flex size-14 items-center justify-center rounded-full bg-accent ring-8 ring-accent/40">
        <Icon aria-hidden className="size-6 text-accent-foreground" />
      </span>
      <p className="font-semibold">{filtered ? labels.files.noMatchTitle : labels.files.emptyTitle}</p>
      <p className="text-sm text-muted-foreground">{filtered ? labels.files.noMatchHint : labels.files.emptyHint}</p>
      {filtered && (
        <Button variant="outline" size="sm" className="mt-2" onClick={onReset}>
          <FilterXIcon data-icon="inline-start" />
          {labels.files.showAll}
        </Button>
      )}
    </div>
  );
}

/** "Mes fichiers": one-click status tabs, search, sortable paginated table. */
export function FilesOverview({ selectedFileId }: { selectedFileId?: string | undefined }) {
  const [search, updateSearch] = useFilesSearchParams();
  const list = useQuery(fileQueries.list(toListParams(search)));
  const summary = useQuery(fileQueries.summary(search.q));
  const onSearchChange = useCallback((q: string | undefined) => updateSearch({ q }), [updateSearch]);
  const resetFilters = useCallback(() => updateSearch({ status: [], q: undefined }), [updateSearch]);
  useStatusAnnouncements(list.data?.content);

  // A page beyond the last one (outdated link, files removed): go to the last page.
  const totalPages = list.data?.page.totalPages ?? 0;
  useEffect(() => {
    if (totalPages > 0 && search.page > totalPages) updateSearch({ page: totalPages }, { replace: true });
  }, [totalPages, search.page, updateSearch]);

  const filtered = Boolean(search.q) || search.status.length > 0;
  const live = list.data?.content.some((file) => !file.terminal) ?? false;
  // Data first: a failed background refresh must not replace valid rows by an error.
  const showError = list.isError && !list.data;

  return (
    <Card
      role="region"
      aria-labelledby="files-title"
      className="workspace-card workspace-files-card gap-0 overflow-hidden py-0"
    >
      <div className="workspace-files-heading flex flex-wrap justify-between gap-3">
        <div className="space-y-0.5">
          <h2 id="files-title" className="workspace-card-title flex flex-wrap items-center gap-2">
            {labels.files.title}
            {live && (
              <span
                title={labels.files.liveHint}
                className="inline-flex items-center gap-2 rounded-full bg-status-scanning-bg px-2.5 py-0.5 text-xs font-medium text-status-scanning"
              >
                <span aria-hidden className="relative flex size-2">
                  <span className="absolute inline-flex size-full animate-ping rounded-full bg-status-scanning opacity-60 motion-reduce:animate-none" />
                  <span className="relative inline-flex size-2 rounded-full bg-status-scanning" />
                </span>
                {labels.files.live}
              </span>
            )}
          </h2>
          <p className="workspace-card-description text-muted-foreground">{labels.files.subtitle}</p>
        </div>
        <FilesSearchInput q={search.q} onSearchChange={onSearchChange} />
      </div>

      <div className="px-5 pb-4">
        <StatusTabs value={search.status} summary={summary.data} onChange={(status) => updateSearch({ status })} />
      </div>

      {showError ? (
        <Alert variant="destructive" className="mx-5 mb-5 w-auto">
          <AlertDescription>
            {labels.files.loadError} {describeError(list.error)}
          </AlertDescription>
          <AlertAction>
            <Button variant="outline" size="sm" onClick={() => void list.refetch()}>
              {labels.retry}
            </Button>
          </AlertAction>
        </Alert>
      ) : (
        <FilesTable
          page={list.data}
          search={search}
          isLoading={list.isPending}
          selectedFileId={selectedFileId}
          onSortChange={(sort) => updateSearch({ sort })}
          onPageChange={(page) => updateSearch({ page })}
          onPageSizeChange={(size) => updateSearch({ size: toPageSize(size) })}
          emptyState={<EmptyState filtered={filtered} onReset={resetFilters} />}
        />
      )}
    </Card>
  );
}
