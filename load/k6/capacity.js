// Essai de capacité : dépôts à débit croissant, palier par palier.
//
//   docker compose --profile load run --rm k6
//   LOAD_RATES=2,4,8 LOAD_STEP=60s docker compose --profile load run --rm k6
//
// Le client se présente en système tiers : jeton Bearer du client confidentiel
// praxedo-integration (client credentials), renouvelé avant son échéance.
//
// Ce qu'on regarde pendant l'essai — le tableau de bord Grafana « Praxedo —
// capacité du service » — et après : `node load/report.mjs`, qui relit
// Prometheus palier par palier. Tant que le débit traité suit le débit déposé
// et que le retard ne grandit pas, le nœud tient ce palier.
//
// Tailles : 128 Kio (50 %), 512 Kio (35 %), 2 Mio (15 %), soit ~0,56 Mio en
// moyenne. k6 garde chaque corps en mémoire : les gros fichiers (500 Mo) ne se
// testent pas ici, mais par le harnais dédié (docs/31, PERF-05) et par
// scripts/demo.sh --big.
import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const API = __ENV.API || 'http://localhost:8080';
// Several nodes: k6 spreads the deposits itself, round robin — no load
// balancer added to the test bench for that.
const TARGETS = (__ENV.API_TARGETS || API).split(',');
const KEYCLOAK = __ENV.KEYCLOAK || 'http://localhost:8081';
const SECRET = __ENV.PRAXEDO_INTEGRATION_SECRET || 'praxedo-local-integration-secret-do-not-reuse';
const RATES = (__ENV.RATES || '1,2,4,8,12').split(',').map(Number);
const STEP = __ENV.STEP || '90s';
const WARMUP_RATE = Number(__ENV.WARMUP_RATE || String(Math.min(20, RATES[0])));
const WARMUP = __ENV.WARMUP || '30s';

const SIZES = [
  { label: '128KiB', bytes: 128 * 1024, weight: 0.5 },
  { label: '512KiB', bytes: 512 * 1024, weight: 0.35 },
  { label: '2MiB', bytes: 2 * 1024 * 1024, weight: 0.15 },
];

// Chaque palier : 10 s de montée, puis STEP au débit visé. Une minute sans
// dépôt à la fin : on voit la file se vider — ou pas.
const stages = [{ target: WARMUP_RATE, duration: WARMUP }, ...RATES.flatMap((rate) => [
  { target: rate, duration: '10s' },
  { target: rate, duration: STEP },
])];
stages.push({ target: 0, duration: '5s' }, { target: 0, duration: '60s' });

export const options = {
  scenarios: {
    deposits: {
      executor: 'ramping-arrival-rate',
      startRate: WARMUP_RATE,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      // Reached, k6 drops iterations instead of sending them: a client-side
      // back-pressure no 429 counts. Raised by the environment for high rates.
      maxVUs: Number(__ENV.MAX_VUS || '120'),
      stages,
    },
  },
  // 429 compte comme un échec : c'est la contre-pression, le signe qu'on a
  // dépassé ce que le nœud absorbe.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:upload}': ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses(202));

// Par utilisateur virtuel : les corps (générés une fois), le jeton et son âge.
const bodies = {};
let token = null;
let tokenAt = 0;

function bearer() {
  if (token && Date.now() - tokenAt < 240_000) {
    return token;
  }
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
  token = answer.json('access_token');
  tokenAt = Date.now();
  return token;
}

function pickSize() {
  let draw = Math.random();
  for (const size of SIZES) {
    if (draw < size.weight) return size;
    draw -= size.weight;
  }
  return SIZES[SIZES.length - 1];
}

export default function () {
  const size = pickSize();
  if (!bodies[size.label]) {
    bodies[size.label] = crypto.randomBytes(size.bytes);
  }
  const target = TARGETS[(__VU + __ITER) % TARGETS.length];
  const response = http.post(`${target}/api/v1/files`, bodies[size.label], {
    headers: {
      Authorization: `Bearer ${bearer()}`,
      'Content-Type': 'application/octet-stream',
      'X-File-Name': `charge-${size.label}-${__VU}-${__ITER}.bin`,
    },
    tags: { name: 'upload', size: size.label },
  });
  check(response, {
    'deposit accepted (202)': (r) => r.status === 202,
  });
}
