/* eslint-disable @typescript-eslint/no-unused-vars -- module augmentation must repeat the type parameters */
import type { CellData, RowData, TableFeatures } from '@tanstack/react-table';

declare module '@tanstack/table-core' {
  // Same type parameters as the declaration being augmented (hence unused here).
  interface ColumnMeta<
    in out TFeatures extends TableFeatures,
    in out TData extends RowData,
    TValue extends CellData = CellData,
  > {
    /** Horizontal alignment of the header and cells (numbers align to the end). */
    align?: 'start' | 'end';
    /** The column takes the remaining width; its content truncates instead of widening the table. */
    grow?: boolean;
  }
}

export {};
