// One synchronized-looking burst: one upload per virtual user. This answers
// "how many deposits can arrive at once?"; capacity.js separately answers how
// many files per second the complete asynchronous pipeline can sustain.
import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const API = __ENV.API || 'http://localhost:8080';
const KEYCLOAK = __ENV.KEYCLOAK || 'http://localhost:8081';
const SECRET = __ENV.PRAXEDO_INTEGRATION_SECRET || 'praxedo-local-integration-secret-do-not-reuse';
const TARGETS = (__ENV.API_TARGETS || API).split(',');
const CONCURRENT_UPLOADS = Number(__ENV.CONCURRENT_UPLOADS || '50');
const FILE_SIZE_BYTES = Number(__ENV.FILE_SIZE_BYTES || String(2 * 1024 * 1024));

// The whole burst crosses the nodes: its last deposit cannot finish before all
// the bytes have arrived. The bound is the burst's volume at the nodes' nominal
// rate (28 MB/s each, docs/capacity-planning) — 50 × 2 MiB on one node gives
// ~3.7 s. The former fixed 2 s was physically out of reach and failed on every run.
const NOMINAL_NODE_BYTES_PER_SECOND = 28e6;
const BURST_P95_MS = Math.ceil(
  (CONCURRENT_UPLOADS * FILE_SIZE_BYTES * 1000) / (NOMINAL_NODE_BYTES_PER_SECOND * TARGETS.length),
);

export const options = {
  scenarios: {
    simultaneousDeposits: {
      executor: 'per-vu-iterations',
      vus: CONCURRENT_UPLOADS,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    'http_req_duration{name:upload}': [`p(95)<${BURST_P95_MS}`],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses(202));

export function setup() {
  const answer = http.post(
    `${KEYCLOAK}/realms/praxedo/protocol/openid-connect/token`,
    { grant_type: 'client_credentials' },
    {
      headers: { Authorization: `Basic ${encoding.b64encode(`praxedo-integration:${SECRET}`)}` },
      tags: { name: 'token' },
      responseCallback: http.expectedStatuses(200),
    },
  );
  if (answer.status !== 200) {
    throw new Error(`Keycloak refused the token request: ${answer.status} ${answer.body}`);
  }
  return { token: answer.json('access_token') };
}

export default function (data) {
  const body = crypto.randomBytes(FILE_SIZE_BYTES);
  const response = http.post(`${TARGETS[__VU % TARGETS.length]}/api/v1/files`, body, {
    headers: {
      Authorization: `Bearer ${data.token}`,
      'Content-Type': 'application/octet-stream',
      'X-File-Name': `concurrent-${__VU}.bin`,
    },
    tags: { name: 'upload', profile: 'simultaneous' },
  });
  check(response, { 'deposit accepted (202)': (r) => r.status === 202 });
}
