import { cn } from 'cn';
import { CheckIcon, LoaderIcon, XIcon } from 'lucide-react';

import type { FileDetail } from '@/api/files/files-schemas';
import { labels } from '@/i18n/messages';
import { formatFullDateTime } from '@/lib/format';

type StepState = 'done' | 'current' | 'upcoming' | 'blocked';

interface Step {
  title: string;
  state: StepState;
  at: string | null;
}

/**
 * Journey of the file, derived from its public status for display only
 * (like the badge): received → analysed → available or blocked.
 */
function stepsFor(file: FileDetail): Step[] {
  const received: Step = { title: labels.detail.stepReceived, state: 'done', at: file.uploadedAt };
  const scanned = file.scan?.scannedAt ?? null;
  switch (file.status) {
    case 'PENDING':
      return [
        received,
        { title: labels.detail.stepWaiting, state: 'current', at: null },
        { title: labels.detail.stepAvailable, state: 'upcoming', at: null },
      ];
    case 'SCANNING':
      return [
        received,
        { title: labels.detail.stepScanningNow, state: 'current', at: null },
        { title: labels.detail.stepAvailable, state: 'upcoming', at: null },
      ];
    case 'AVAILABLE':
      return [
        received,
        { title: labels.detail.stepScanning, state: 'done', at: scanned },
        { title: labels.detail.stepAvailable, state: 'done', at: file.statusChangedAt },
      ];
    case 'INFECTED':
    case 'UNSCANNABLE':
      return [
        received,
        { title: labels.detail.stepScanning, state: 'done', at: scanned },
        { title: labels.detail.stepBlocked, state: 'blocked', at: file.statusChangedAt },
      ];
    case 'FAILED':
      return [
        received,
        { title: labels.detail.stepScanning, state: 'blocked', at: file.statusChangedAt },
        { title: labels.detail.stepBlocked, state: 'blocked', at: null },
      ];
    case 'UNKNOWN':
      return [
        received,
        { title: labels.detail.stepScanning, state: 'upcoming', at: null },
        { title: labels.detail.stepAvailable, state: 'upcoming', at: null },
      ];
  }
}

const DOT_CLASS: Record<StepState, string> = {
  done: 'bg-status-available text-solid-foreground',
  current: 'bg-status-scanning text-solid-foreground ring-4 ring-status-scanning-bg',
  upcoming: 'bg-card text-muted-foreground ring-1 ring-border',
  blocked: 'bg-status-infected text-solid-foreground',
};

function StepDot({ state }: { state: StepState }) {
  return (
    <span
      aria-hidden
      className={cn('relative z-10 flex size-6 shrink-0 items-center justify-center rounded-full', DOT_CLASS[state])}
    >
      {state === 'done' && <CheckIcon className="size-3.5" />}
      {state === 'blocked' && <XIcon className="size-3.5" />}
      {state === 'current' && <LoaderIcon className="size-3.5 animate-spin motion-reduce:animate-none" />}
      {state === 'upcoming' && <span className="size-1.5 rounded-full bg-muted-foreground/50" />}
    </span>
  );
}

export function AnalysisTimeline({ file }: { file: FileDetail }) {
  const steps = stepsFor(file);
  return (
    <section className="space-y-3">
      <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{labels.detail.timeline}</h3>
      <ol className="relative space-y-4 before:absolute before:top-3 before:bottom-3 before:left-3 before:w-px before:-translate-x-1/2 before:bg-border">
        {steps.map((step) => (
          <li key={step.title} className="flex items-start gap-3">
            <StepDot state={step.state} />
            <div className="min-w-0 pt-0.5">
              <p
                className={cn(
                  'text-sm font-medium',
                  step.state === 'upcoming' && 'text-muted-foreground',
                  step.state === 'blocked' && 'text-status-infected',
                )}
              >
                {step.title}
              </p>
              {step.at && (
                <time dateTime={step.at} className="text-xs text-muted-foreground">
                  {formatFullDateTime(step.at)}
                </time>
              )}
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}
