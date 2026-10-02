import type { ReactTable } from '@tanstack/react-table';
import { ChevronLeftIcon, ChevronRightIcon, ChevronsLeftIcon, ChevronsRightIcon } from 'lucide-react';

import type { FileSummary } from '@/api/files/files-schemas';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { labels } from '@/i18n/messages';

import { PAGE_SIZES } from '../lib/files-search';
import type { filesTableFeatures } from '../lib/files-table-features';

interface FilesPaginationProps {
  table: ReactTable<typeof filesTableFeatures, FileSummary>;
  totalElements: number;
}

/** Page navigation driven by the table's pagination API (server-side pages). */
export function FilesPagination({ table, totalElements }: FilesPaginationProps) {
  const { pageIndex, pageSize } = table.state.pagination;
  const pageCount = table.getPageCount();
  const from = pageIndex * pageSize + 1;
  const to = Math.min(from + pageSize - 1, totalElements);

  return (
    <nav aria-label="Pagination" className="workspace-pagination flex flex-wrap items-center justify-between gap-3">
      <p className="text-muted-foreground tabular-nums">{labels.pagination.range(from, to, totalElements)}</p>
      <div className="flex flex-wrap items-center gap-4">
        <div className="flex items-center gap-2">
          <span id="page-size-label" className="text-muted-foreground">
            {labels.pagination.rowsPerPage}
          </span>
          <Select value={String(pageSize)} onValueChange={(value) => table.setPageSize(Number(value))}>
            <SelectTrigger size="sm" className="w-18" aria-labelledby="page-size-label">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PAGE_SIZES.map((size) => (
                <SelectItem key={size} value={String(size)}>
                  {size}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <p className="tabular-nums" aria-live="polite">
          {labels.pagination.page(pageIndex + 1, pageCount)}
        </p>
        <div className="flex items-center gap-1">
          <Button
            variant="outline"
            size="icon-sm"
            onClick={() => table.firstPage()}
            disabled={!table.getCanPreviousPage()}
            aria-label={labels.pagination.first}
          >
            <ChevronsLeftIcon />
          </Button>
          <Button
            variant="outline"
            size="icon-sm"
            onClick={() => table.previousPage()}
            disabled={!table.getCanPreviousPage()}
            aria-label={labels.pagination.previous}
          >
            <ChevronLeftIcon />
          </Button>
          <Button
            variant="outline"
            size="icon-sm"
            onClick={() => table.nextPage()}
            disabled={!table.getCanNextPage()}
            aria-label={labels.pagination.next}
          >
            <ChevronRightIcon />
          </Button>
          <Button
            variant="outline"
            size="icon-sm"
            onClick={() => table.lastPage()}
            disabled={!table.getCanNextPage()}
            aria-label={labels.pagination.last}
          >
            <ChevronsRightIcon />
          </Button>
        </div>
      </div>
    </nav>
  );
}
