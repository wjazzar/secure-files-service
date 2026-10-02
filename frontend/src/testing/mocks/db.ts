import type { FileDetail, FileSummary, KnownFileStatus } from '@/api/files/files-schemas';

/**
 * In-memory fake of the service, faithful to the contract.
 *
 * A file goes PENDING → SCANNING → its verdict, as time passes; the scenario
 * is chosen from the file name, so that every status can be shown by
 * uploading a file with the right name:
 *   "eicar"               → INFECTED (EICAR test signature)
 *   "chiffre"/"encrypted" → UNSCANNABLE (encrypted archive)
 *   "echec"/"fail"        → FAILED after 3 attempts
 *   anything else         → AVAILABLE
 */
type Scenario = 'clean' | 'infected' | 'unscannable' | 'failed';

export interface MockFile {
  id: string;
  filename: string;
  sizeBytes: number;
  contentType: string;
  uploadedAt: number;
  scenario: Scenario;
  sha256: string;
  /** Owner's id: the user who deposited it. */
  ownerId: string;
}

const PENDING_MS = 2_500;
const SCANNING_MS = 4_000;

function scenarioFor(filename: string): Scenario {
  const name = filename.toLowerCase();
  if (name.includes('eicar')) return 'infected';
  if (name.includes('chiffre') || name.includes('chiffré') || name.includes('encrypted')) return 'unscannable';
  if (name.includes('echec') || name.includes('échec') || name.includes('fail')) return 'failed';
  return 'clean';
}

const CONTENT_TYPES: Record<string, string> = {
  pdf: 'application/pdf',
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  zip: 'application/zip',
  txt: 'text/plain',
  csv: 'text/csv',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
};

function contentTypeFor(filename: string): string {
  const extension = filename.split('.').pop()?.toLowerCase() ?? '';
  return CONTENT_TYPES[extension] ?? 'application/octet-stream';
}

function fakeSha256(seed: string): string {
  let hash = '';
  let value = 0;
  for (let i = 0; hash.length < 64; i++) {
    value = (value * 31 + seed.charCodeAt(i % seed.length) + i) % 0xffffffff;
    hash += (value % 16).toString(16);
  }
  return hash;
}

function statusAt(file: MockFile, now: number): KnownFileStatus {
  const elapsed = now - file.uploadedAt;
  if (elapsed < PENDING_MS) return 'PENDING';
  if (elapsed < PENDING_MS + SCANNING_MS) return 'SCANNING';
  switch (file.scenario) {
    case 'clean':
      return 'AVAILABLE';
    case 'infected':
      return 'INFECTED';
    case 'unscannable':
      return 'UNSCANNABLE';
    case 'failed':
      return 'FAILED';
  }
}

/** The server's rule: each user only sees their own file space. */
export function isVisibleTo(file: MockFile, userId: string): boolean {
  return file.ownerId === userId;
}

export function toSummary(file: MockFile, now: number): FileSummary {
  const status = statusAt(file, now);
  const elapsed = now - file.uploadedAt;
  const changedAfter = status === 'PENDING' ? 0 : status === 'SCANNING' ? PENDING_MS : PENDING_MS + SCANNING_MS;
  return {
    id: file.id,
    filename: file.filename,
    sizeBytes: file.sizeBytes,
    contentType: file.contentType,
    status,
    downloadable: status === 'AVAILABLE',
    terminal: status !== 'PENDING' && status !== 'SCANNING',
    uploadedAt: new Date(file.uploadedAt).toISOString(),
    statusChangedAt: new Date(file.uploadedAt + Math.min(changedAfter, elapsed)).toISOString(),
  };
}

export function toDetail(file: MockFile, now: number): FileDetail {
  const summary = toSummary(file, now);
  const scannedAt = new Date(file.uploadedAt + PENDING_MS + SCANNING_MS).toISOString();
  const engine = {
    engine: 'ClamAV',
    engineVersion: '1.4.3',
    signatureVersion: 'daily.cvd 27781',
    scannedAt,
    durationMs: 1_840,
  };
  const verdict = {
    AVAILABLE: { result: 'CLEAN' as const, threatName: null, ...engine },
    INFECTED: { result: 'INFECTED' as const, threatName: 'Eicar-Test-Signature', ...engine },
    UNSCANNABLE: { result: 'UNSCANNABLE' as const, threatName: null, ...engine },
  };
  return {
    ...summary,
    sha256: file.sha256,
    scanAttempts: summary.status === 'PENDING' ? 0 : summary.status === 'FAILED' ? 3 : 1,
    scan:
      summary.status === 'AVAILABLE' || summary.status === 'INFECTED' || summary.status === 'UNSCANNABLE'
        ? verdict[summary.status]
        : null,
    statusReason:
      summary.status === 'UNSCANNABLE'
        ? 'ENCRYPTED_ARCHIVE'
        : summary.status === 'FAILED'
          ? 'SCAN_ATTEMPTS_EXHAUSTED'
          : null,
    links: {
      self: `/api/v1/files/${file.id}`,
      content: summary.downloadable ? contentUrl(file) : null,
    },
  };
}

const simulatedContents = new Map<string, string>();

/**
 * Where the interface downloads a file from. The real service answers
 * `/api/v1/files/{id}/content`, reached by a native navigation — which the MSW
 * service worker does not intercept. In a browser, the mock therefore hands
 * out an in-page object URL instead; the interface code is the same for both.
 */
function contentUrl(file: MockFile): string {
  if (typeof URL.createObjectURL !== 'function') return `/api/v1/files/${file.id}/content`;
  let url = simulatedContents.get(file.id);
  if (!url) {
    const content = new Blob([`Contenu simulé de « ${file.filename} » (bouchon MSW).\n`], {
      type: 'application/octet-stream',
    });
    url = URL.createObjectURL(content);
    simulatedContents.set(file.id, url);
  }
  return url;
}

export function createMockFile(
  filename: string,
  sizeBytes: number,
  uploadedAt: number,
  ownerId: string,
  id?: string,
): MockFile {
  const fileId = id ?? crypto.randomUUID();
  return {
    id: fileId,
    filename,
    sizeBytes,
    contentType: contentTypeFor(filename),
    uploadedAt,
    scenario: scenarioFor(filename),
    sha256: fakeSha256(fileId),
    ownerId,
  };
}

// [name, size, owner]: every demo account owns a mix of statuses in its own space.
const SEED: readonly (readonly [string, number, string])[] = [
  ['rapport-intervention-2026-09.pdf', 1_245_184, 'u-alice'],
  ['photo-compteur-avant.jpg', 3_481_600, 'u-alice'],
  ['photo-compteur-apres.jpg', 3_302_400, 'u-alice'],
  ['bon-de-commande-4812.pdf', 184_320, 'u-bob'],
  ['eicar-test.txt', 68, 'u-bob'],
  ['archive-chiffree.zip', 52_428_800, 'u-alice'],
  ['planning-techniciens.xlsx', 96_256, 'u-claire'],
  ['devis-maintenance-annuelle.pdf', 412_672, 'u-bob'],
  ['releve-echec-analyse.csv', 20_480, 'u-bob'],
  ['schema-installation.png', 2_097_152, 'u-alice'],
  ['contrat-cadre-signé.pdf', 845_824, 'u-claire'],
  ['notice-équipement.pdf', 5_242_880, 'u-alice'],
  ['export-interventions-aout.csv', 1_048_576, 'u-claire'],
  ['procès-verbal-réception.docx', 131_072, 'u-bob'],
  ['photos-chantier.zip', 157_286_400, 'u-alice'],
  ['facture-F2026-0917.pdf', 98_304, 'u-bob'],
  ['attestation-assurance.pdf', 256_000, 'u-claire'],
  ['check-list-sécurité.pdf', 64_512, 'u-alice'],
  ['rapport-intervention-2026-08.pdf', 1_120_256, 'u-bob'],
  ['inventaire-pièces.xlsx', 73_728, 'u-claire'],
  ['plan-accès-site.png', 1_572_864, 'u-alice'],
  ['compte-rendu-réunion.docx', 45_056, 'u-claire'],
  ['certificat-conformité.pdf', 327_680, 'u-bob'],
  ['journal-maintenance.txt', 12_288, 'u-claire'],
];

export interface MockDb {
  files: Map<string, MockFile>;
  /** Idempotency-Key (scoped to the caller) → file id: a replayed upload returns the first file. */
  idempotency: Map<string, string>;
  /** Access token → user id, filled by the simulated identity provider. */
  tokens: Map<string, string>;
  /** Failed logins per username, to simulate a lockout. */
  failedLogins: Map<string, { count: number; lockedUntil: number }>;
}

export function createMockDb(now = Date.now()): MockDb {
  const files = new Map<string, MockFile>();
  SEED.forEach(([filename, size, ownerId], index) => {
    // Spread over the last days; all terminal.
    const file = createMockFile(filename, size, now - (index + 1) * 3_700_000, ownerId);
    files.set(file.id, file);
  });
  return { files, idempotency: new Map(), tokens: new Map(), failedLogins: new Map() };
}
