import { rowPaginationFeature, rowSortingFeature, tableFeatures } from '@tanstack/react-table';

/**
 * Features registered on the file table (TanStack Table v9 only exposes the
 * APIs of registered features). No row model is registered: sorting and
 * pagination happen on the server (manual mode).
 */
export const filesTableFeatures = tableFeatures({ rowSortingFeature, rowPaginationFeature });
