import { ChevronRightIcon, CloudUploadIcon, DownloadIcon, ScanSearchIcon } from 'lucide-react';
import { Fragment } from 'react';

import { labels } from '@/i18n/messages';

const STEP_ICONS = [CloudUploadIcon, ScanSearchIcon, DownloadIcon] as const;

/**
 * Compact page introduction: title, one sentence, and the security guarantee
 * as a one-line pipeline (upload → antivirus scan → download).
 */
export function PageIntro({ title, description }: { title: string; description: string }) {
  return (
    <div className="workspace-intro flex flex-col lg:flex-row lg:items-end lg:justify-between">
      <div>
        <p className="workspace-eyebrow">{labels.appEyebrow}</p>
        <h1 className="workspace-title">{title}</h1>
        <p className="workspace-lede text-muted-foreground">{description}</p>
      </div>

      <ol
        aria-label={labels.pipelineLabel}
        className="workspace-pipeline flex flex-wrap items-center self-start border bg-card lg:self-auto"
      >
        {labels.steps.map((step, index) => {
          const Icon = STEP_ICONS[index] ?? CloudUploadIcon;
          return (
            <Fragment key={step.title}>
              {index > 0 && (
                <ChevronRightIcon aria-hidden className="workspace-step-chevron text-muted-foreground/60" />
              )}
              <li title={step.text} className="workspace-step flex items-center font-medium">
                <span className="workspace-step-icon flex items-center justify-center bg-accent text-accent-foreground">
                  <Icon aria-hidden className="size-4" />
                </span>
                {step.title}
              </li>
            </Fragment>
          );
        })}
      </ol>
    </div>
  );
}
