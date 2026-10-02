import { cn } from 'cn';
import { CircleCheckIcon, FilesIcon, LoaderIcon, type LucideIcon, ShieldAlertIcon, XIcon } from 'lucide-react';

import type { FilesSummary, KnownFileStatus } from '@/api/files/files-schemas';
import { labels } from '@/i18n/messages';

import { STATUS_DISPLAY } from '../lib/status-display';

type TabTone = 'neutral' | 'available' | 'scanning' | 'blocked';

interface TabDefinition {
  key: string;
  label: string;
  icon: LucideIcon;
  tone: TabTone;
  /** Statuses shown by this tab (empty = all). */
  statuses: readonly KnownFileStatus[];
}

/** Display grouping of the statuses. Counts come from the server (`/files/summary`). */
const TABS: readonly TabDefinition[] = [
  { key: 'all', label: labels.stats.total, icon: FilesIcon, tone: 'neutral', statuses: [] },
  {
    key: 'available',
    label: labels.stats.available,
    icon: CircleCheckIcon,
    tone: 'available',
    statuses: ['AVAILABLE'],
  },
  {
    key: 'in-progress',
    label: labels.stats.inProgress,
    icon: LoaderIcon,
    tone: 'scanning',
    statuses: ['PENDING', 'SCANNING'],
  },
  {
    key: 'blocked',
    label: labels.stats.blocked,
    icon: ShieldAlertIcon,
    tone: 'blocked',
    statuses: ['INFECTED', 'UNSCANNABLE', 'FAILED'],
  },
];

const ICON_TONE: Record<TabTone, string> = {
  neutral: 'text-muted-foreground',
  available: 'text-status-available',
  scanning: 'text-status-scanning',
  blocked: 'text-status-infected',
};

function sameStatuses(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((status) => b.includes(status));
}

function count(summary: FilesSummary | undefined, tab: TabDefinition): number | null {
  if (!summary) return null;
  if (tab.statuses.length === 0) return summary.total;
  return tab.statuses.reduce((sum, status) => sum + (summary.byStatus[status] ?? 0), 0);
}

interface StatusTabsProps {
  value: readonly KnownFileStatus[];
  summary: FilesSummary | undefined;
  onChange: (statuses: KnownFileStatus[]) => void;
}

/**
 * One-click status filter. Exactly one tab is selected, and it stands out
 * (`workspace.css`): what the table shows is visible at a glance.
 */
export function StatusTabs({ value, summary, onChange }: StatusTabsProps) {
  const selected = TABS.find((tab) => sameStatuses(value, tab.statuses));

  return (
    <div className="flex flex-wrap items-center gap-2">
      <div role="group" aria-label={labels.stats.label} className="workspace-status-tabs flex flex-wrap">
        {TABS.map((tab) => {
          const active = tab === selected;
          const Icon = tab.icon;
          const value = count(summary, tab);
          return (
            <button
              key={tab.key}
              type="button"
              aria-pressed={active}
              onClick={() => onChange([...tab.statuses])}
              className={cn(
                'workspace-status-tab inline-flex shrink-0 items-center gap-2 rounded-lg font-medium whitespace-nowrap transition-colors',
                'focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none',
                !active && 'text-muted-foreground hover:bg-card hover:text-foreground',
              )}
            >
              <Icon aria-hidden className={cn('size-4', active ? 'text-current' : ICON_TONE[tab.tone])} />
              {tab.label}
              <span
                className={cn(
                  'workspace-status-count min-w-6 rounded-full px-1.5 py-px text-center text-xs font-semibold tabular-nums',
                  active ? 'text-current' : 'bg-card text-muted-foreground ring-1 ring-border',
                )}
              >
                {value ?? '–'}
              </span>
            </button>
          );
        })}
      </div>

      {/* A filter that matches no tab (hand-written URL): shown explicitly, removable in one click. */}
      {!selected && (
        <button
          type="button"
          onClick={() => onChange([])}
          className="inline-flex items-center gap-1.5 rounded-full bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
        >
          {value.map((status) => STATUS_DISPLAY[status].label).join(', ')}
          <XIcon aria-hidden className="size-3.5" />
          <span className="sr-only">{labels.files.clearFilters}</span>
        </button>
      )}
    </div>
  );
}
