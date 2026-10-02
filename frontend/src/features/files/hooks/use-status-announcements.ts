import { useEffect, useRef } from 'react';

import type { FileStatus, FileSummary } from '@/api/files/files-schemas';
import { useAnnounce } from '@/hooks/use-announce';

import { STATUS_DISPLAY } from '../lib/status-display';

/**
 * Announces status changes to screen readers ("rapport.pdf : Disponible").
 * Only changes are announced, not the files shown on first render.
 */
export function useStatusAnnouncements(files: readonly FileSummary[] | undefined): void {
  const announce = useAnnounce();
  const previous = useRef<Map<string, FileStatus> | null>(null);

  useEffect(() => {
    if (!files) return;
    const before = previous.current;
    if (before) {
      const changes = files
        .filter((file) => before.has(file.id) && before.get(file.id) !== file.status)
        .map((file) => `${file.filename} : ${STATUS_DISPLAY[file.status].label}`);
      if (changes.length > 0) announce(changes.join('. '));
    }
    previous.current = new Map(files.map((file) => [file.id, file.status]));
  }, [files, announce]);
}
