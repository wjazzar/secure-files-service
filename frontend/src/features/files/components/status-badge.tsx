import { cva } from 'class-variance-authority';
import { cn } from 'cn';

import type { FileStatus } from '@/api/files/files-schemas';
import { Badge } from '@/components/ui/badge';

import { STATUS_DISPLAY, type StatusTone } from '../lib/status-display';

const toneVariants = cva('gap-1.5 rounded-full', {
  variants: {
    tone: {
      pending: 'bg-status-pending-bg text-status-pending',
      scanning: 'bg-status-scanning-bg text-status-scanning',
      available: 'bg-status-available-bg text-status-available',
      infected: 'bg-status-infected-bg text-status-infected',
      warning: 'bg-status-warning-bg text-status-warning',
      unknown: 'bg-status-unknown-bg text-status-unknown',
    } satisfies Record<StatusTone, string>,
  },
});

export function StatusBadge({ status, className }: { status: FileStatus; className?: string }) {
  const display = STATUS_DISPLAY[status];
  const Icon = display.icon;
  return (
    <Badge variant="secondary" className={cn(toneVariants({ tone: display.tone }), className)} data-status={status}>
      <Icon aria-hidden className={cn(display.spinning && 'animate-spin motion-reduce:animate-none')} />
      {display.label}
    </Badge>
  );
}
