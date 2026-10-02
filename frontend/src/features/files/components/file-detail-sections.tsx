import { CopyIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { toast } from 'sonner';

import type { FileDetail } from '@/api/files/files-schemas';
import { Button } from '@/components/ui/button';
import { labels } from '@/i18n/messages';
import { formatBytes, formatDuration, formatFullDateTime } from '@/lib/format';

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-2">
      <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</h3>
      <div className="workspace-detail-panel border bg-card px-3">{children}</div>
    </section>
  );
}

function Item({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[8.5rem_1fr] items-baseline gap-3 py-2 text-sm">
      <dt className="text-muted-foreground">{term}</dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </div>
  );
}

/** Technical details of the verdict: engine, signatures, date, duration, attempts. */
export function ScanVerdictSection({ file }: { file: FileDetail }) {
  const scan = file.scan;
  return (
    <Section title={labels.detail.analysis}>
      {scan ? (
        <dl className="divide-y divide-border">
          <Item term={labels.detail.engine}>
            <span className="font-medium">{scan.engine}</span>
            {scan.engineVersion ? <span className="text-muted-foreground"> {scan.engineVersion}</span> : null}
          </Item>
          {scan.signatureVersion && (
            <Item term={labels.detail.signatures}>
              <code className="font-mono text-xs">{scan.signatureVersion}</code>
            </Item>
          )}
          <Item term={labels.detail.scannedAt}>
            <time dateTime={scan.scannedAt}>{formatFullDateTime(scan.scannedAt)}</time>
          </Item>
          <Item term={labels.detail.duration}>
            <span className="tabular-nums">{formatDuration(scan.durationMs)}</span>
          </Item>
          <Item term={labels.detail.attempts}>
            <span className="tabular-nums">{file.scanAttempts}</span>
          </Item>
        </dl>
      ) : (
        <p className="py-3 text-sm text-muted-foreground">
          {file.terminal ? labels.detail.noVerdict : labels.detail.noVerdictYet}
        </p>
      )}
    </Section>
  );
}

function copySha256(sha256: string) {
  navigator.clipboard.writeText(sha256).then(
    () => toast.success(labels.detail.copied),
    () => undefined,
  );
}

/** Size, detected type, SHA-256 (copyable), dates. */
export function FileMetadataSection({ file }: { file: FileDetail }) {
  return (
    <Section title={labels.detail.information}>
      <dl className="divide-y divide-border">
        <Item term={labels.detail.size}>
          <span className="tabular-nums">{formatBytes(file.sizeBytes)}</span>
        </Item>
        <Item term={labels.detail.type}>
          <code className="font-mono text-xs">{file.contentType}</code>
        </Item>
        <Item term={labels.detail.sha256}>
          <div className="flex items-start gap-1">
            <code className="min-w-0 flex-1 font-mono text-xs leading-5 break-all">{file.sha256}</code>
            <Button
              variant="ghost"
              size="icon-xs"
              onClick={() => copySha256(file.sha256)}
              aria-label={`${labels.detail.copy} : ${labels.detail.sha256}`}
            >
              <CopyIcon />
            </Button>
          </div>
        </Item>
        <Item term={labels.detail.uploadedAt}>
          <time dateTime={file.uploadedAt}>{formatFullDateTime(file.uploadedAt)}</time>
        </Item>
        <Item term={labels.detail.statusChangedAt}>
          <time dateTime={file.statusChangedAt}>{formatFullDateTime(file.statusChangedAt)}</time>
        </Item>
      </dl>
    </Section>
  );
}
