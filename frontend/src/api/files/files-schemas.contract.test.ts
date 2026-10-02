/// <reference types="node" />
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { parse } from 'yaml';
import type { z } from 'zod';

import { LogoutSchema, SessionSchema } from '@/lib/auth/session-adapter';

import { ERROR_CODES, ProblemSchema } from '../problem-schema';
import {
  FILE_STATUSES,
  FileDetailSchema,
  FilePageSchema,
  FileSummarySchema,
  FileLinksSchema,
  FilesSummarySchema,
  PageMetadataSchema,
  SCAN_RESULTS,
  SORT_OPTIONS,
  STATUS_REASON_CODES,
  ScanVerdictSchema,
} from './files-schemas';

/**
 * Drift guard: the Zod schemas are written by hand, so this test checks them
 * against contracts/openapi.yaml, the single source of truth. A contract
 * change not carried over to the interface fails the build. Tests run from
 * the frontend/ folder, hence the path relative to the working directory.
 */

interface OpenApiSchema {
  enum?: string[];
  properties?: Record<string, OpenApiSchema>;
  required?: string[];
  allOf?: OpenApiSchema[];
}

const contract = parse(readFileSync(resolve(process.cwd(), '../contracts/openapi.yaml'), 'utf8')) as {
  components: { schemas: Record<string, OpenApiSchema> };
  paths: Record<string, Record<string, { parameters?: { name: string; schema?: OpenApiSchema }[] }>>;
};
const schemas = contract.components.schemas;

function requiredFields(name: string): string[] {
  const schema = schemas[name];
  if (!schema) throw new Error(`Schema ${name} missing from the contract`);
  return [...(schema.required ?? []), ...(schema.allOf ?? []).flatMap((part) => part.required ?? [])];
}

function zodKeys(schema: z.ZodObject): string[] {
  return Object.keys(schema.shape);
}

describe('Zod schemas follow contracts/openapi.yaml', () => {
  it.each([
    ['FileStatus', FILE_STATUSES],
    ['StatusReasonCode', STATUS_REASON_CODES],
    ['ErrorCode', ERROR_CODES],
  ] as const)('enumeration %s has exactly the same values', (name, values) => {
    expect([...values].sort()).toEqual([...(schemas[name]?.enum ?? [])].sort());
  });

  it('the scan results are exactly those of ScanVerdict.result', () => {
    expect([...SCAN_RESULTS].sort()).toEqual([...(schemas.ScanVerdict?.properties?.result?.enum ?? [])].sort());
  });

  it('sort options are exactly those of GET /files', () => {
    const sort = contract.paths['/api/v1/files']?.get?.parameters?.find((p) => p.name === 'sort');
    expect([...SORT_OPTIONS].sort()).toEqual([...(sort?.schema?.enum ?? [])].sort());
  });

  it.each([
    ['FileSummary', FileSummarySchema],
    ['FileDetail', FileDetailSchema],
    ['ScanVerdict', ScanVerdictSchema],
    ['FilePage', FilePageSchema],
    ['PageMetadata', PageMetadataSchema],
    ['FilesSummary', FilesSummarySchema],
    ['FileLinks', FileLinksSchema],
    ['Problem', ProblemSchema],
    ['Session', SessionSchema],
    ['Logout', LogoutSchema],
  ] as const)('every required field of %s is read by the interface', (name, schema) => {
    expect(zodKeys(schema)).toEqual(expect.arrayContaining(requiredFields(name)));
  });
});
