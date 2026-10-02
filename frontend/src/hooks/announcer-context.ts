import { createContext } from 'react';

export type Announce = (message: string) => void;

/** Writes into the single `aria-live` region mounted at the root (components/live-region). */
export const AnnouncerContext = createContext<Announce>(() => undefined);
