import {
  CircleCheckIcon,
  CircleHelpIcon,
  ClockIcon,
  LoaderIcon,
  type LucideIcon,
  ShieldAlertIcon,
  TriangleAlertIcon,
  XCircleIcon,
} from 'lucide-react';

import type { FileStatus } from '@/api/files/files-schemas';
import { statusLabels } from '@/i18n/messages';

export type StatusTone = 'pending' | 'scanning' | 'available' | 'infected' | 'warning' | 'unknown';

interface StatusDisplay {
  label: string;
  icon: LucideIcon;
  tone: StatusTone;
  /** Animated icon while the server is working on the file. */
  spinning?: boolean;
}

/**
 * Status → label, icon and tone. Display only: no decision is ever taken from
 * a status (downloads depend on `downloadable`, polling on `terminal`).
 *
 * Color gives the family, icon and label give the exact status: the status is
 * never carried by color alone (WCAG 1.4.1).
 *
 * Typed `Record<FileStatus, …>`: a status added to the schema without a
 * display does not compile, and an unknown value from the server is parsed as
 * `UNKNOWN`, which has its neutral display (rule F-2).
 */
export const STATUS_DISPLAY: Record<FileStatus, StatusDisplay> = {
  PENDING: { label: statusLabels.PENDING, icon: ClockIcon, tone: 'pending' },
  SCANNING: { label: statusLabels.SCANNING, icon: LoaderIcon, tone: 'scanning', spinning: true },
  AVAILABLE: { label: statusLabels.AVAILABLE, icon: CircleCheckIcon, tone: 'available' },
  INFECTED: { label: statusLabels.INFECTED, icon: ShieldAlertIcon, tone: 'infected' },
  UNSCANNABLE: { label: statusLabels.UNSCANNABLE, icon: TriangleAlertIcon, tone: 'warning' },
  FAILED: { label: statusLabels.FAILED, icon: XCircleIcon, tone: 'warning' },
  UNKNOWN: { label: statusLabels.UNKNOWN, icon: CircleHelpIcon, tone: 'unknown' },
};
