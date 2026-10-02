import { cn } from 'cn';
import { CloudUploadIcon, FilesIcon, HardDriveIcon, ShieldCheckIcon } from 'lucide-react';
import { type DragEvent, useId, useRef, useState } from 'react';

import { Button } from '@/components/ui/button';
import { labels } from '@/i18n/messages';

interface UploadDropzoneProps {
  onFilesSelected: (files: File[]) => void;
}

const PILLS = [
  { icon: HardDriveIcon, text: labels.upload.pillMaxSize },
  { icon: ShieldCheckIcon, text: labels.upload.pillScan },
  { icon: FilesIcon, text: labels.upload.pillMultiple },
] as const;

/**
 * Drag-and-drop area with a keyboard-accessible file picker. Files are passed
 * on as `File` objects: their content is never read here.
 */
export function UploadDropzone({ onFilesSelected }: UploadDropzoneProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const hintId = useId();
  const [isDragging, setIsDragging] = useState(false);

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setIsDragging(false);
    const files = Array.from(event.dataTransfer.files);
    if (files.length > 0) onFilesSelected(files);
  };

  return (
    <div
      onDragOver={(event) => {
        event.preventDefault();
        event.dataTransfer.dropEffect = 'copy';
        setIsDragging(true);
      }}
      onDragLeave={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setIsDragging(false);
      }}
      onDrop={handleDrop}
      data-dragging={isDragging || undefined}
      className={cn(
        'workspace-dropzone group relative flex flex-col items-center justify-center text-center transition-all',
        'data-dragging:scale-[1.01] motion-reduce:data-dragging:scale-100',
      )}
    >
      <span className="workspace-dropzone-icon relative flex items-center justify-center bg-card shadow-sm ring-1 ring-border">
        <span
          aria-hidden
          className="workspace-dropzone-halo absolute inset-0 ring-4 ring-ring/10 transition group-data-dragging:ring-8 group-data-dragging:ring-ring/20"
        />
        <CloudUploadIcon aria-hidden className="size-7 text-accent-foreground" />
      </span>
      <div className="space-y-1">
        <p className="workspace-dropzone-title font-semibold">
          {isDragging ? labels.upload.dropActive : labels.upload.dropHint}
        </p>
        <p className="workspace-dropzone-or text-muted-foreground">{labels.upload.or}</p>
      </div>
      <Button
        className="workspace-primary-action"
        size="lg"
        type="button"
        onClick={() => inputRef.current?.click()}
        aria-describedby={hintId}
      >
        {labels.upload.browse}
      </Button>
      <ul id={hintId} aria-label={labels.upload.constraints} className="flex flex-wrap justify-center gap-1.5">
        {PILLS.map(({ icon: Icon, text }) => (
          <li
            key={text}
            className="workspace-pill inline-flex items-center rounded-full bg-card text-muted-foreground ring-1 ring-border"
          >
            <Icon aria-hidden className="size-3.5" />
            {text}
          </li>
        ))}
      </ul>
      <input
        ref={inputRef}
        type="file"
        multiple
        hidden
        data-testid="file-input"
        onChange={(event) => {
          const files = Array.from(event.currentTarget.files ?? []);
          // Reset so that selecting the same file again still triggers a change.
          event.currentTarget.value = '';
          if (files.length > 0) onFilesSelected(files);
        }}
      />
    </div>
  );
}
