import { FileDetailSchema, FileStatusSchema } from './files-schemas';

const detail = {
  id: '5b1f6f2e-7d2a-4b8e-9d3c-0f6f3c1b2a11',
  filename: 'rapport.pdf',
  sizeBytes: 1024,
  contentType: 'application/pdf',
  status: 'AVAILABLE',
  downloadable: true,
  terminal: true,
  uploadedAt: '2026-09-25T10:00:00Z',
  statusChangedAt: '2026-09-25T10:00:07.123Z',
  sha256: 'a'.repeat(64),
  scanAttempts: 1,
  scan: {
    result: 'CLEAN',
    engine: 'ClamAV',
    scannedAt: '2026-09-25T10:00:07+02:00',
    durationMs: 1200,
  },
  statusReason: null,
  links: { self: '/api/v1/files/5b1f6f2e-7d2a-4b8e-9d3c-0f6f3c1b2a11', content: null },
};

describe('tolerant enumerations (rule F-2)', () => {
  it('keeps known statuses', () => {
    expect(FileStatusSchema.parse('INFECTED')).toBe('INFECTED');
  });

  it('maps a status added later by the server to UNKNOWN instead of failing', () => {
    expect(FileStatusSchema.parse('QUARANTINED_FOR_REVIEW')).toBe('UNKNOWN');
  });

  it('still rejects a missing or non-string status', () => {
    expect(FileStatusSchema.safeParse(null).success).toBe(false);
    expect(FileStatusSchema.safeParse(undefined).success).toBe(false);
    expect(FileStatusSchema.safeParse(3).success).toBe(false);
  });

  it('parses a whole file whose status and verdict are unknown to this version', () => {
    const parsed = FileDetailSchema.parse({
      ...detail,
      status: 'NEW_STATUS',
      statusReason: 'NEW_REASON',
      scan: { ...detail.scan, result: 'NEW_RESULT' },
    });
    expect(parsed.status).toBe('UNKNOWN');
    expect(parsed.statusReason).toBe('UNKNOWN');
    expect(parsed.scan?.result).toBe('UNKNOWN');
  });
});

describe('FileDetailSchema', () => {
  it('accepts a contract-conforming file', () => {
    expect(FileDetailSchema.safeParse(detail).success).toBe(true);
  });

  it('rejects a body that breaks the contract', () => {
    expect(FileDetailSchema.safeParse({ ...detail, downloadable: 'yes' }).success).toBe(false);
  });
});
