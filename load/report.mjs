// Relit Prometheus après un essai de charge et rend, palier par palier, ce que
// le nœud a tenu : débit déposé et traité, retard, lag par fichier, ressources.
//
//   node load/report.mjs                          dernier essai, paliers par défaut
//   node load/report.mjs --rates 2,4,8 --step 60  paliers de l'essai lancé
//   node load/report.mjs --start 2026-09-28T14:05:00Z
//
// Sans --start, le début de l'essai est retrouvé dans Prometheus : le dernier
// bloc continu de dépôts des trois dernières heures. Les paliers doivent être
// ceux passés à k6 (LOAD_RATES, LOAD_STEP), montée de 10 s comprise.
//
// Un palier est « tenu » quand la file ne grandit pas (moins de 5 % des
// fichiers déposés pendant le palier restent en plus à la fin) et que le plus
// ancien fichier en attente a moins de 2 minutes (SLO-6). Sur un palier court,
// ce critère sépare un palier sain d'un palier saturé, pas un palier soutenable
// d'un palier juste au-dessus du genou : à 80 fichiers/s, 5 % font 240
// fichiers, la moitié de la file d'admission. Le genou se confirme par un
// palier long.
//
// L'invariant est relu sur tout l'essai, pas seulement à la fin : c'est la
// garantie du service, sa preuve ne peut pas être un instantané.
//
// Plusieurs nœuds : les débits s'additionnent, les jauges lues en base (file,
// invariant) sont les mêmes sur chaque nœud et se prennent en maximum, les
// ressources (CPU, tas, GC) se lisent par nœud, le plus chargé.
//
// Un second tableau dit où part le temps : l'antivirus (durée par appel et
// nombre d'analyses en cours), le ramasse-miettes, l'attente d'une connexion
// à la base.
import { execSync } from 'node:child_process';

import { RAMP_SECONDS, stageWindows } from './stages.mjs';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, arg, index, all) => {
    if (arg.startsWith('--')) pairs.push([arg.slice(2), all[index + 1]]);
    return pairs;
  }, []),
);
const PROMETHEUS = args.prometheus ?? process.env.PROMETHEUS ?? 'http://localhost:9090';
const RATES = (args.rates ?? process.env.LOAD_RATES ?? '1,2,4,8,12').split(',').map(Number);
const STEP = Number(String(args.step ?? process.env.LOAD_STEP ?? '90').replace(/s$/, ''));
const WARMUP = Number(String(args.warmup ?? process.env.LOAD_WARMUP ?? '30').replace(/s$/, ''));
const APP = 'application="praxedo-securefiles"';
const UPLOADS = `http_server_requests_seconds_count{${APP},uri="/api/v1/files",method="POST"}`;

async function query(expr, time) {
  const url = `${PROMETHEUS}/api/v1/query?query=${encodeURIComponent(expr)}&time=${time}`;
  const body = await (await fetch(url)).json();
  if (body.status !== 'success') throw new Error(`${expr}: ${body.error}`);
  const value = body.data.result[0]?.value?.[1];
  return value === undefined ? NaN : Number(value);
}

async function labelValues(expr, time, label) {
  const url = `${PROMETHEUS}/api/v1/query?query=${encodeURIComponent(expr)}&time=${time}`;
  const body = await (await fetch(url)).json();
  return body.status === 'success' ? [...new Set(body.data.result.map((series) => series.metric[label]))] : [];
}

async function lastRunStart() {
  const end = Math.floor(Date.now() / 1000);
  const url = `${PROMETHEUS}/api/v1/query_range?query=${encodeURIComponent(`sum(rate(${UPLOADS}[15s]))`)}`
    + `&start=${end - 3 * 3600}&end=${end}&step=5`;
  const body = await (await fetch(url)).json();
  const points = (body.data.result[0]?.values ?? []).map(([t, v]) => [Number(t), Number(v)]);
  let last = points.length - 1;
  while (last >= 0 && !(points[last][1] > 0.05)) last--;
  if (last < 0) throw new Error('No deposits found in Prometheus over the last three hours: pass --start');
  let first = last;
  while (first > 0 && points[first - 1][1] > 0.05) first--;
  return points[first][0] - 5;
}

const mb = (bytes) => (bytes / 1e6).toFixed(1);
const fixed = (value, digits = 1) => (Number.isFinite(value) ? value.toFixed(digits) : '—');

const start = args.start ? Math.floor(Date.parse(args.start) / 1000) : await lastRunStart();
const rows = [];
for (const { rate, from: holdStart, to: holdEnd } of stageWindows({ start, rates: RATES, step: STEP, warmup: WARMUP })) {
  if (holdEnd > Date.now() / 1000) break;
  const w = `${STEP}s`;
  const at = (expr) => query(expr, holdEnd);
  const scan = (part) => `sum(increase(praxedo_scan_duration_seconds_${part}{${APP}}[${w}]))`;
  const dbAcquire = (part, pool) =>
    `sum(increase(hikaricp_connections_acquire_seconds_${part}{${APP},pool="${pool}"}[${w}]))`;
  const dbWait = (pool) => at(`${dbAcquire('sum', pool)} / ${dbAcquire('count', pool)}`);
  const dbPending = (pool) => at(`max_over_time(max(hikaricp_connections_pending{${APP},pool="${pool}"})[${w}:5s])`);
  const [scanMean, scanP95, scansInFlight, gcShare, apiWait, queueWait, apiPending, queuePending] = await Promise.all([
    at(`${scan('sum')} / ${scan('count')}`),
    at(`histogram_quantile(0.95, sum by (le) (increase(praxedo_scan_duration_seconds_bucket{${APP}}[${w}])))`),
    at(`${scan('sum')} / ${STEP}`),
    at(`max(sum by (instance) (increase(jvm_gc_pause_seconds_sum{${APP}}[${w}]))) / ${STEP}`),
    dbWait('api'),
    dbWait('queue'),
    dbPending('api'),
    dbPending('queue'),
  ]);
  const [inFiles, outFiles, inBytes, outBytes, depthStart, depthEnd, oldest, lagP95, uploadP95, cpu, heap, rejected] =
    await Promise.all([
      at(`sum(increase(${UPLOADS.replace('}', ',status="202"}')}[${w}])) / ${STEP}`),
      at(`sum(increase(praxedo_pipeline_lag_seconds_count{${APP}}[${w}])) / ${STEP}`),
      at(`sum(increase(praxedo_upload_bytes_total{${APP}}[${w}])) / ${STEP}`),
      at(`sum(increase(praxedo_pipeline_completed_bytes_total{${APP}}[${w}])) / ${STEP}`),
      query(`max(praxedo_queue_depth{${APP}})`, holdStart),
      at(`max(praxedo_queue_depth{${APP}})`),
      at(`max_over_time(max(praxedo_queue_oldest_pending_age_seconds{${APP}})[${w}:5s])`),
      at(`histogram_quantile(0.95, sum by (le) (increase(praxedo_pipeline_lag_seconds_bucket{${APP}}[${w}])))`),
      at(`histogram_quantile(0.95, sum by (le) (increase(http_server_requests_seconds_bucket{${APP},uri="/api/v1/files",method="POST"}[${w}])))`),
      at(`avg_over_time(max(process_cpu_usage{${APP}})[${w}:5s])`),
      at(`max_over_time(max(sum by (instance) (jvm_memory_used_bytes{${APP},area="heap"}))[${w}:5s])`),
      at(`sum(increase(praxedo_admission_rejected_total{${APP}}[${w}]))`),
    ]);
  const grew = depthEnd - depthStart;
  const holds = grew <= Math.max(3, 0.05 * inFiles * STEP) && oldest < 120 && !(rejected > 0);
  rows.push({
    rate, inFiles, outFiles, inBytes, outBytes, depthEnd, grew, oldest, lagP95, uploadP95, cpu, heap, rejected, holds,
    scanMean, scanP95, scansInFlight, gcShare, apiWait, queueWait, apiPending, queuePending,
  });
}

const now = Math.floor(Date.now() / 1000);
const [cpus, heapMax, nodes, collectors, invariantMax] = await Promise.all([
  query(`max(system_cpu_count{${APP}})`, start + 60),
  query(`max(sum by (instance) (jvm_memory_max_bytes{${APP},area="heap"} > 0))`, start + 60),
  query(`count(process_cpu_usage{${APP}})`, start + 60),
  labelValues(`jvm_gc_pause_seconds_count{${APP}}`, now, 'gc'),
  query(`max_over_time(max(praxedo_invariant_violations{${APP}})[${Math.max(60, now - start)}s:5s])`, now),
]);
let host = '';
try {
  host = execSync('docker info --format "{{.NCPU}} CPU, {{.MemTotal}} octets, {{.OperatingSystem}}"').toString().trim();
  host = host.replace(/(\d+) octets/, (_, bytes) => `${(Number(bytes) / 2 ** 30).toFixed(1)} Gio`);
} catch {
  host = 'inconnu (docker info indisponible)';
}

console.log(`Essai commencé le ${new Date(start * 1000).toISOString()} — échauffement ${WARMUP} s, paliers de ${STEP} s (+${RAMP_SECONDS} s de montée)`);
console.log(`Nœuds mesurés : ${fixed(nodes, 0)} × ${cpus} CPU vu(s) par la JVM, tas maximal ${mb(heapMax)} Mo, `
  + `ramasse-miettes ${collectors.join(' + ') || 'inconnu'} ; machine Docker : ${host}`);
console.log();
console.log('CPU, tas : le nœud le plus chargé.');
console.log();
console.log('| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |');
console.log('|---|---|---|---|---|---|---|---|---|---|---|---|');
for (const r of rows) {
  console.log(
    `| ${r.rate} fichiers/s | ${fixed(r.inFiles, 2)}/s (${mb(r.inBytes)} Mo/s) | ${fixed(r.outFiles, 2)}/s | ${mb(r.outBytes)} Mo/s `
      + `| ${fixed(r.depthEnd, 0)} (${r.grew >= 0 ? '+' : ''}${fixed(r.grew, 0)}) | ${fixed(r.oldest)} s | ${fixed(r.lagP95)} s `
      + `| ${fixed(r.uploadP95 * 1000, 0)} ms | ${fixed(r.cpu * 100, 0)} % | ${mb(r.heap)} Mo | ${fixed(r.rejected, 0)} | ${r.holds ? 'oui' : '**non**'} |`,
  );
}
console.log();
console.log('Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne '
  + '(à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; '
  + 'base : attente moyenne d\'une connexion et demandes en attente au pire moment, pour le pool de l\'API '
  + 'puis pour celui de la file de travail.');
console.log();
console.log('| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente BD api / file | En attente max api / file |');
console.log('|---|---|---|---|---|---|---|');
for (const r of rows) {
  console.log(
    `| ${r.rate} fichiers/s | ${fixed(r.scanMean * 1000, 0)} ms | ${fixed(r.scanP95 * 1000, 0)} ms | ${fixed(r.scansInFlight, 2)} `
      + `| ${fixed(r.gcShare * 100, 1)} % | ${fixed(r.apiWait * 1000, 1)} / ${fixed(r.queueWait * 1000, 1)} ms `
      + `| ${fixed(r.apiPending, 0)} / ${fixed(r.queuePending, 0)} |`,
  );
}
console.log();
// NaN means the gauge could not be read: unknown, never reported as zero.
console.log(`Invariant (praxedo_invariant_violations), maximum sur tout l'essai : ${fixed(invariantMax, 0)}`);
