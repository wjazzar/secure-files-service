import { type ReactNode, useCallback, useState } from 'react';

import { AnnouncerContext } from '@/hooks/announcer-context';

/**
 * The single polite live region of the application (WCAG 4.1.3). Status
 * changes are announced here, without moving the focus.
 */
export function LiveRegionProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState('');

  // Clearing first makes the same message announced twice in a row.
  const announce = useCallback((next: string) => {
    setMessage('');
    window.setTimeout(() => setMessage(next), 50);
  }, []);

  return (
    <AnnouncerContext value={announce}>
      {children}
      <div role="status" aria-live="polite" aria-atomic="true" className="sr-only">
        {message}
      </div>
    </AnnouncerContext>
  );
}
