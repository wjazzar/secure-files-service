import { cva } from 'class-variance-authority';
import { cn } from 'cn';
import {
  FileArchiveIcon,
  FileIcon,
  FileImageIcon,
  FileSpreadsheetIcon,
  FileTextIcon,
  type LucideIcon,
  PresentationIcon,
} from 'lucide-react';

type FileKind = 'document' | 'image' | 'archive' | 'spreadsheet' | 'presentation' | 'other';

const KIND_BY_EXTENSION: Record<string, FileKind> = {
  pdf: 'document',
  doc: 'document',
  docx: 'document',
  odt: 'document',
  txt: 'document',
  rtf: 'document',
  md: 'document',
  png: 'image',
  jpg: 'image',
  jpeg: 'image',
  gif: 'image',
  webp: 'image',
  svg: 'image',
  heic: 'image',
  zip: 'archive',
  rar: 'archive',
  '7z': 'archive',
  gz: 'archive',
  tar: 'archive',
  xls: 'spreadsheet',
  xlsx: 'spreadsheet',
  ods: 'spreadsheet',
  csv: 'spreadsheet',
  ppt: 'presentation',
  pptx: 'presentation',
  odp: 'presentation',
};

const ICONS: Record<FileKind, LucideIcon> = {
  document: FileTextIcon,
  image: FileImageIcon,
  archive: FileArchiveIcon,
  spreadsheet: FileSpreadsheetIcon,
  presentation: PresentationIcon,
  other: FileIcon,
};

const tileVariants = cva('inline-flex shrink-0 items-center justify-center rounded-lg', {
  variants: {
    kind: {
      document: 'bg-status-scanning-bg text-status-scanning',
      image: 'bg-file-image-bg text-file-image',
      archive: 'bg-status-warning-bg text-status-warning',
      spreadsheet: 'bg-status-available-bg text-status-available',
      presentation: 'bg-file-presentation-bg text-file-presentation',
      other: 'bg-muted text-muted-foreground',
    } satisfies Record<FileKind, string>,
    size: {
      sm: 'size-8 [&>svg]:size-4',
      md: 'size-9 [&>svg]:size-[1.125rem]',
      lg: 'size-12 [&>svg]:size-6',
    },
  },
  defaultVariants: { size: 'md' },
});

/** Kind of file, for display only: guessed from the extension of the name. */
function fileKind(filename: string): FileKind {
  const extension = filename.includes('.') ? (filename.split('.').pop()?.toLowerCase() ?? '') : '';
  return KIND_BY_EXTENSION[extension] ?? 'other';
}

/** Colored tile with an icon matching the kind of file. Decorative. */
export function FileTypeIcon({
  filename,
  size,
  className,
}: {
  filename: string;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}) {
  const kind = fileKind(filename);
  const Icon = ICONS[kind];
  return (
    <span aria-hidden className={cn(tileVariants({ kind, size }), className)}>
      <Icon />
    </span>
  );
}
