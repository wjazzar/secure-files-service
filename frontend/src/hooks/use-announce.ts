import { useContext } from 'react';

import { type Announce, AnnouncerContext } from './announcer-context';

/** Returns a function that announces a message to screen readers. */
export function useAnnounce(): Announce {
  return useContext(AnnouncerContext);
}
