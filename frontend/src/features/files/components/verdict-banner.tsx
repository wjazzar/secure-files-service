import { cva } from 'class-variance-authority';
import {
  CircleHelpIcon,
  ClockIcon,
  LoaderIcon,
  type LucideIcon,
  ShieldAlertIcon,
  ShieldCheckIcon,
  ShieldXIcon,
} from 'lucide-react';

import type { FileDetail } from '@/api/files/files-schemas';
import { labels, statusLabels, statusReasonMessages } from '@/i18n/messages';

import { STATUS_DISPLAY, type StatusTone } from '../lib/status-display';

const bannerVariants = cva('flex gap-4 rounded-xl border p-4', {
  variants: {
    tone: {
      available: 'border-status-available/25 bg-status-available-bg text-status-available',
      infected: 'border-status-infected/25 bg-status-infected-bg text-status-infected',
      warning: 'border-status-warning/25 bg-status-warning-bg text-status-warning',
      scanning: 'border-status-scanning/25 bg-status-scanning-bg text-status-scanning',
      pending: 'border-border bg-status-pending-bg text-status-pending',
      unknown: 'border-border bg-status-unknown-bg text-status-unknown',
    } satisfies Record<StatusTone, string>,
  },
});

interface BannerContent {
  icon: LucideIcon;
  title: string;
  lines: string[];
}

/**
 * Display of the verdict. For a blocked file: factual title, consequence,
 * cause, next step — without alarmism. Never decides anything: the download
 * button depends on `downloadable` only. Tone and animation are the status's
 * own (`STATUS_DISPLAY`), as on the badge.
 */
function contentFor(file: FileDetail): BannerContent {
  const reason = statusReasonMessages[file.statusReason ?? 'UNKNOWN'];
  switch (file.status) {
    case 'AVAILABLE':
      return { icon: ShieldCheckIcon, title: labels.detail.verdictClean, lines: [labels.steps[2].text] };
    case 'INFECTED':
      return {
        icon: ShieldAlertIcon,
        title: labels.detail.blockedTitle.INFECTED,
        lines: [labels.detail.blockedConsequence, labels.detail.infectedAdvice],
      };
    case 'UNSCANNABLE':
      return {
        icon: ShieldXIcon,
        title: labels.detail.blockedTitle.UNSCANNABLE,
        lines: [labels.detail.blockedConsequence, reason, labels.detail.unscannableAdvice],
      };
    case 'FAILED':
      return {
        icon: ShieldXIcon,
        title: labels.detail.blockedTitle.FAILED,
        lines: [labels.detail.blockedConsequence, reason, labels.detail.failedAdvice],
      };
    case 'SCANNING':
      return { icon: LoaderIcon, title: statusLabels.SCANNING, lines: [labels.detail.noVerdictYet] };
    case 'PENDING':
      return {
        icon: ClockIcon,
        title: labels.detail.verdictPending,
        lines: [file.statusReason ? reason : labels.detail.noVerdictYet],
      };
    case 'UNKNOWN':
      return { icon: CircleHelpIcon, title: statusLabels.UNKNOWN, lines: [] };
  }
}

export function VerdictBanner({ file }: { file: FileDetail }) {
  const content = contentFor(file);
  const { tone, spinning } = STATUS_DISPLAY[file.status];
  const Icon = content.icon;
  const threat = file.status === 'INFECTED' ? file.scan?.threatName : null;
  return (
    <div className={bannerVariants({ tone })}>
      <span className="flex size-11 shrink-0 items-center justify-center rounded-full bg-card/70 shadow-sm">
        <Icon aria-hidden className={spinning ? 'size-6 animate-spin motion-reduce:animate-none' : 'size-6'} />
      </span>
      <div className="min-w-0 space-y-1 text-sm">
        <p className="text-base font-semibold">{content.title}</p>
        {threat && (
          <p>
            {labels.detail.threat} :{' '}
            <code className="rounded bg-card/70 px-1.5 py-0.5 font-mono text-xs font-semibold">{threat}</code>
          </p>
        )}
        {content.lines.map((line) => (
          <p key={line} className="text-current/85">
            {line}
          </p>
        ))}
      </div>
    </div>
  );
}
