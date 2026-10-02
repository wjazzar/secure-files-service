import { useLocation, useNavigate, useParams } from 'react-router';

import { FileDetailSheet } from '@/features/files/components/file-detail-sheet';

/**
 * `/files/:fileId`: the detail panel over the table. Closing goes back to the
 * list with its search, filters and page intact.
 */
export function FileDetailRoute() {
  const { fileId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  if (!fileId) return null;
  return (
    <FileDetailSheet
      key={fileId}
      fileId={fileId}
      onClose={() => void navigate({ pathname: '/', search: location.search })}
    />
  );
}
