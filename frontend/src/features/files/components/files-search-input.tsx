import { cn } from 'cn';
import { SearchIcon, XIcon } from 'lucide-react';
import { useEffect, useEffectEvent, useId, useState } from 'react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useDebouncedValue } from '@/hooks/use-debounced-value';
import { labels } from '@/i18n/messages';

const SEARCH_DEBOUNCE_MS = 300;

interface FilesSearchInputProps {
  q: string | undefined;
  onSearchChange: (q: string | undefined) => void;
}

/** Search by name: the URL follows the input once typing pauses (300 ms). */
export function FilesSearchInput({ q, onSearchChange }: FilesSearchInputProps) {
  const inputId = useId();
  const [text, setText] = useState(q ?? '');
  const debounced = useDebouncedValue(text, SEARCH_DEBOUNCE_MS);

  // Reads the latest `q` and callback without being an effect dependency:
  // only a NEW debounced input triggers a search. Otherwise, after a reset,
  // the stale debounced text would be written back to the URL.
  const applySearch = useEffectEvent((value: string) => {
    const next = value.trim() || undefined;
    if (next !== q) onSearchChange(next);
  });
  useEffect(() => applySearch(debounced), [debounced]);

  // The input follows the URL when it changes elsewhere (back button, reset).
  const [lastQ, setLastQ] = useState(q);
  if (q !== lastQ) {
    setLastQ(q);
    if ((q ?? '') !== text.trim()) setText(q ?? '');
  }

  return (
    <div className="relative w-full sm:w-60">
      <label htmlFor={inputId} className="sr-only">
        {labels.files.searchLabel}
      </label>
      <SearchIcon
        aria-hidden
        className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground"
      />
      <Input
        id={inputId}
        type="search"
        value={text}
        onChange={(event) => setText(event.target.value)}
        placeholder={labels.files.searchPlaceholder}
        maxLength={100}
        className={cn('workspace-search-input pr-8 pl-8', q && 'border-primary ring-2 ring-primary/15')}
      />
      {text && (
        <Button
          variant="ghost"
          size="icon-xs"
          className="absolute top-1/2 right-1.5 -translate-y-1/2"
          onClick={() => setText('')}
          aria-label={labels.files.clearSearch}
        >
          <XIcon />
        </Button>
      )}
    </div>
  );
}
