import {
  functionalUpdate,
  type PaginationState,
  type SortingState,
  type Updater,
  useTable,
} from '@tanstack/react-table';
import { ArrowDownIcon, ArrowUpDownIcon, ArrowUpIcon } from 'lucide-react';
import { cn } from 'cn';

import { type FilePage, type FileSummary, SORT_OPTIONS, type SortOption } from '@/api/files/files-schemas';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { labels } from '@/i18n/messages';

import { type FilesSearch, lastReachablePage } from '../lib/files-search';
import { filesTableFeatures } from '../lib/files-table-features';
import { filesColumns } from './files-columns';
import { FilesPagination } from './files-pagination';

const EMPTY_ROWS: FileSummary[] = [];

function toSortingState(sort: SortOption): SortingState {
  const [id = 'uploadedAt', direction] = sort.split(',');
  return [{ id, desc: direction === 'desc' }];
}

function toSortOption(sorting: SortingState): SortOption | null {
  const first = sorting[0];
  if (!first) return null;
  const candidate = `${first.id},${first.desc ? 'desc' : 'asc'}`;
  return SORT_OPTIONS.find((option) => option === candidate) ?? null;
}

interface FilesTableProps {
  page: FilePage | undefined;
  search: FilesSearch;
  isLoading: boolean;
  selectedFileId: string | undefined;
  onSortChange: (sort: SortOption) => void;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
  emptyState: React.ReactNode;
}

/**
 * TanStack Table in server mode: it neither sorts nor paginates rows itself.
 * Its state is the URL state, and each change is written back to the URL,
 * which triggers the next query.
 */
export function FilesTable({
  page,
  search,
  isLoading,
  selectedFileId,
  onSortChange,
  onPageChange,
  onPageSizeChange,
  emptyState,
}: FilesTableProps) {
  const sorting = toSortingState(search.sort);
  const pagination: PaginationState = { pageIndex: search.page - 1, pageSize: search.size };

  const table = useTable({
    features: filesTableFeatures,
    columns: filesColumns,
    data: page?.content ?? EMPTY_ROWS,
    getRowId: (row) => row.id,
    manualSorting: true,
    manualPagination: true,
    // « Last page » stops where the API stops serving pages (contract: 10 000 files deep).
    rowCount: Math.min(page?.page.totalElements ?? 0, lastReachablePage(search.size) * search.size),
    enableSortingRemoval: false,
    enableMultiSort: false,
    state: { sorting, pagination },
    onSortingChange: (updater: Updater<SortingState>) => {
      const next = toSortOption(functionalUpdate(updater, sorting));
      if (next) onSortChange(next);
    },
    onPaginationChange: (updater: Updater<PaginationState>) => {
      const next = functionalUpdate(updater, pagination);
      if (next.pageSize !== pagination.pageSize) onPageSizeChange(next.pageSize);
      else onPageChange(next.pageIndex + 1);
    },
  });

  const rows = table.getRowModel().rows;
  const showSkeleton = isLoading && !page;

  return (
    <div className="workspace-files-table">
      <div className="border-y">
        <Table className="min-w-155">
          <TableCaption className="sr-only">{labels.files.tableCaption}</TableCaption>
          <TableHeader className="workspace-table-header [&_th]:font-semibold [&_th]:text-muted-foreground [&_th]:uppercase [&_th:first-child]:pl-5 [&_th:last-child]:pr-5">
            {table.getHeaderGroups().map((group) => (
              <TableRow key={group.id} className="hover:bg-transparent">
                {group.headers.map((header) => {
                  const column = header.column;
                  const sorted = column.getIsSorted();
                  const align = column.columnDef.meta?.align;
                  return (
                    <TableHead
                      key={header.id}
                      aria-sort={sorted === 'asc' ? 'ascending' : sorted === 'desc' ? 'descending' : undefined}
                      className={cn(align === 'end' && 'text-right')}
                    >
                      {header.isPlaceholder ? null : column.getCanSort() ? (
                        <button
                          type="button"
                          onClick={column.getToggleSortingHandler()}
                          className={cn(
                            '-mx-2 inline-flex items-center gap-1 rounded-md px-2 py-1 uppercase hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none',
                            sorted && 'text-foreground',
                            align === 'end' && 'flex-row-reverse',
                          )}
                        >
                          <table.FlexRender header={header} />
                          {sorted === 'asc' ? (
                            <ArrowUpIcon aria-hidden className="size-3.5" />
                          ) : sorted === 'desc' ? (
                            <ArrowDownIcon aria-hidden className="size-3.5" />
                          ) : (
                            <ArrowUpDownIcon aria-hidden className="size-3.5 opacity-40" />
                          )}
                          {sorted && (
                            <span className="sr-only">
                              , {sorted === 'asc' ? labels.files.sortAscending : labels.files.sortDescending}
                            </span>
                          )}
                        </button>
                      ) : (
                        <table.FlexRender header={header} />
                      )}
                    </TableHead>
                  );
                })}
              </TableRow>
            ))}
          </TableHeader>
          <TableBody className="workspace-table-body">
            {showSkeleton ? (
              Array.from({ length: 5 }, (_, index) => (
                <TableRow key={index} className="h-16">
                  {table.getAllLeafColumns().map((column, cellIndex) => (
                    <TableCell key={column.id} className="first:pl-5 last:pr-5">
                      <Skeleton className={cellIndex === 0 ? 'h-8 w-56' : 'h-4 w-full max-w-24'} />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : rows.length === 0 ? (
              <TableRow className="hover:bg-transparent">
                <TableCell colSpan={filesColumns.length} className="py-16">
                  {emptyState}
                </TableCell>
              </TableRow>
            ) : (
              rows.map((row) => (
                <TableRow
                  key={row.id}
                  data-state={row.id === selectedFileId ? 'selected' : undefined}
                  className="workspace-file-row relative transition-colors"
                >
                  {row.getAllCells().map((cell) => (
                    <TableCell
                      key={cell.id}
                      className={cn(
                        'first:pl-5 last:pr-5',
                        cell.column.columnDef.meta?.align === 'end' && 'text-right',
                        cell.column.columnDef.meta?.grow && 'w-full max-w-0',
                      )}
                    >
                      <table.FlexRender cell={cell} />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>
      {page && page.page.totalElements > 0 && (
        <div className="px-5 py-3">
          <FilesPagination table={table} totalElements={page.page.totalElements} />
        </div>
      )}
    </div>
  );
}
