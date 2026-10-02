import { useQueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';
import { Outlet, useParams } from 'react-router';

import { fileQueries } from '@/api/files/files-queries';
import { PageIntro } from '@/components/layout/page-intro';
import { FilesOverview } from '@/features/files/components/files-overview';
import { UploadPanel } from '@/features/upload/components/upload-panel';
import { labels } from '@/i18n/messages';

/**
 * Main page: the user's own file space. Composes the two features, which
 * know nothing of each other: the link between "a file was uploaded" and
 * "refresh the list" lives here. Isolation between users is enforced by the
 * API (another user's file answers 404), not by this page.
 */
export function FilesRoute() {
  const queryClient = useQueryClient();
  const { fileId } = useParams();

  const onUploaded = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: fileQueries.all() });
  }, [queryClient]);

  return (
    <div className="workspace-content space-y-7">
      <PageIntro title={labels.appTitle} description={labels.appTagline} />
      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
        <div className="lg:sticky lg:top-6">
          <UploadPanel onUploaded={onUploaded} />
        </div>
        <FilesOverview selectedFileId={fileId} />
      </div>
      {/* Detail panel (child route /files/:fileId) */}
      <Outlet />
    </div>
  );
}
