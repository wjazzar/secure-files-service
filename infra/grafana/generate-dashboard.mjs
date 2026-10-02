// Génère dashboards/praxedo-capacite.json — le tableau de bord de capacité
// provisionné dans Grafana. Le JSON est versionné pour que Grafana le lise tel
// quel ; c'est ce script qu'on relit et qu'on modifie :
//
//   node infra/grafana/generate-dashboard.mjs
//
// Chaque requête filtre sur l'étiquette application (management.metrics.tags)
// et agrège par sum/max : le tableau reste juste avec plusieurs nœuds.
import { writeFileSync } from 'node:fs';

const DS ={ type: 'prometheus', uid: 'prometheus' };
const APP = 'application="praxedo-securefiles"';
let id = 0;
let y = 0;
const panels = [];

function thresholds(steps) {
  // A first step without a value is the base colour; green unless the panel says otherwise.
  const base = steps.length && steps[0].value === null ? [] : [{ color: 'green', value: null }];
  return { mode: 'absolute', steps: [...base, ...steps] };
}

function row(title) {
  panels.push({ type: 'row', title, collapsed: false, id: ++id, gridPos: { h: 1, w: 24, x: 0, y }, panels: [] });
  y += 1;
}

function text(content, h = 3) {
  panels.push({
    type: 'text', id: ++id, title: '', transparent: true,
    gridPos: { h, w: 24, x: 0, y },
    options: { mode: 'markdown', content },
  });
  y += h;
}

// A line of stat panels, `w` wide each.
function stats(items, w = 4, h = 5) {
  let x = 0;
  for (const item of items) {
    panels.push({
      type: 'stat', id: ++id, title: item.title, description: item.description,
      datasource: DS, gridPos: { h, w, x, y },
      targets: [{ refId: 'A', expr: item.expr, datasource: DS, instant: false, legendFormat: item.title }],
      fieldConfig: {
        defaults: {
          unit: item.unit ?? 'short', decimals: item.decimals, noValue: item.noValue ?? '—',
          color: { mode: 'thresholds' },
          thresholds: thresholds(item.steps ?? []),
          min: item.min,
          max: item.max,
        },
        overrides: [],
      },
      options: {
        reduceOptions: { calcs: ['lastNotNull'], fields: '', values: false },
        colorMode: 'value', graphMode: item.graph === false ? 'none' : 'area',
        justifyMode: 'auto', orientation: 'auto', textMode: 'value', wideLayout: true,
        showPercentChange: false,
      },
    });
    x += w;
  }
  y += h;
}

// A line of time series, sharing the width.
function series(items, h = 8) {
  const w = Math.floor(24 / items.length);
  let x = 0;
  for (const item of items) {
    panels.push({
      type: 'timeseries', id: ++id, title: item.title, description: item.description,
      datasource: DS, gridPos: { h, w, x, y },
      targets: item.targets.map(([expr, legend], index) => ({
        refId: String.fromCharCode(65 + index), expr, legendFormat: legend, datasource: DS,
      })),
      fieldConfig: {
        defaults: {
          unit: item.unit ?? 'short', min: item.min ?? 0, max: item.max,
          color: { mode: 'palette-classic' },
          custom: {
            drawStyle: 'line', lineWidth: 2, fillOpacity: item.stack ? 35 : 12, gradientMode: 'opacity',
            showPoints: 'never', spanNulls: true, axisSoftMin: 0,
            stacking: { mode: item.stack ? 'normal' : 'none', group: 'A' },
            thresholdsStyle: { mode: item.steps ? 'line+area' : 'off' },
          },
          thresholds: thresholds(item.steps ?? []),
        },
        overrides: [],
      },
      options: {
        legend: { displayMode: 'list', placement: 'bottom', showLegend: true },
        tooltip: { mode: 'multi', sort: 'desc' },
      },
    });
    x += w;
  }
  y += h;
}

const throughputBytes = `sum(rate(praxedo_pipeline_completed_bytes_total{${APP}}[1m]))`;
const throughputFiles = `sum(rate(praxedo_pipeline_lag_seconds_count{${APP}}[1m]))`;
const lagQuantile = (q, window = '5m') =>
  `histogram_quantile(${q}, sum by (le) (rate(praxedo_pipeline_lag_seconds_bucket{${APP}}[${window}])))`;

text(
  "**Lire ce tableau.** Le service tient la charge tant que le **retard** reste stable : les fichiers " +
    "en attente et l'âge du plus ancien ne grandissent pas, et le **débit** sortant suit le débit entrant. " +
    "Le nœud de référence est bridé à **1 processeur et 2 Go** (`docker-compose.yml`) : les chiffres lus " +
    "ici sont ceux de ce gabarit. Mesures et méthode : `load/README.md`.",
  3,
);

row('Tient-il la charge ? — retard et débit, en ce moment');
stats([
  {
    title: 'Retard — fichiers en attente',
    description: "Fichiers déposés dont l'analyse ou la mise à disposition n'est pas finie (praxedo_queue_depth). Au-delà de 500, les dépôts reçoivent 429.",
    expr: `sum(praxedo_queue_depth{${APP}})`,
    steps: [{ color: 'orange', value: 100 }, { color: 'red', value: 400 }],
  },
  {
    title: 'Retard — volume en attente',
    description: 'Taille cumulée des fichiers en attente (praxedo_queue_depth_bytes) : le temps d’analyse croît avec la taille.',
    expr: `sum(praxedo_queue_depth_bytes{${APP}})`, unit: 'decbytes',
    steps: [{ color: 'orange', value: 500e6 }, { color: 'red', value: 2e9 }],
  },
  {
    title: 'Retard — plus ancien en attente',
    description: "Depuis combien de temps attend le plus ancien fichier non terminé. Au-delà de 2 min, l'objectif SLO-6 est manqué.",
    expr: `max(praxedo_queue_oldest_pending_age_seconds{${APP}})`, unit: 's',
    steps: [{ color: 'orange', value: 60 }, { color: 'red', value: 120 }],
  },
  {
    title: 'Temps pour résorber le retard',
    description: 'Volume en attente ÷ débit sortant actuel. Vide quand rien ne sort.',
    expr: `sum(praxedo_queue_depth_bytes{${APP}}) / (${throughputBytes} > 0)`, unit: 's',
    steps: [{ color: 'orange', value: 60 }, { color: 'red', value: 120 }],
  },
  {
    title: 'Débit — fichiers traités',
    description: 'Fichiers arrivés à leur état final (disponible ou bloqué), par seconde, sur la dernière minute.',
    expr: throughputFiles, unit: 'suffix: fichiers/s', decimals: 2,
  },
  {
    title: 'Débit — volume traité',
    description: 'Octets des fichiers arrivés à leur état final, par seconde : le débit mesuré par la taille.',
    expr: throughputBytes, unit: 'Bps',
  },
]);
stats([
  {
    title: 'Lag par fichier — p95',
    description: 'Du dépôt à l’état final, pour 95 % des fichiers des 5 dernières minutes (praxedo_pipeline_lag). Objectif SLO-6 : < 2 min.',
    expr: lagQuantile(0.95), unit: 's',
    steps: [{ color: 'orange', value: 60 }, { color: 'red', value: 120 }],
  },
  {
    title: 'Fichiers dans l’objectif (< 2 min)',
    description: 'Part des fichiers terminés en moins de 2 minutes, sur les 5 dernières minutes.',
    expr: `sum(rate(praxedo_pipeline_lag_seconds_bucket{${APP},le="120.0"}[5m])) / sum(rate(praxedo_pipeline_lag_seconds_count{${APP}}[5m]))`,
    unit: 'percentunit', min: 0, max: 1,
    steps: [{ color: 'red', value: null }, { color: 'orange', value: 0.9 }, { color: 'green', value: 0.95 }],
  },
  {
    title: 'Processeur du service',
    description: 'Part du processeur alloué au conteneur (1 CPU) utilisée par la JVM (process_cpu_usage).',
    expr: `max(process_cpu_usage{${APP}})`, unit: 'percentunit', min: 0, max: 1,
    steps: [{ color: 'orange', value: 0.7 }, { color: 'red', value: 0.9 }],
  },
  {
    title: 'Mémoire — tas utilisé',
    description: 'Tas utilisé ÷ tas maximal (75 % des 2 Go du conteneur).',
    expr: `sum(jvm_memory_used_bytes{${APP},area="heap"}) / sum(jvm_memory_max_bytes{${APP},area="heap"} > 0)`,
    unit: 'percentunit', min: 0, max: 1,
    steps: [{ color: 'orange', value: 0.75 }, { color: 'red', value: 0.9 }],
  },
  {
    title: 'Dépôts refusés (429) — 5 min',
    description: 'Contre-pression : dépôts refusés parce que la file dépassait son seuil.',
    expr: `sum(increase(praxedo_admission_rejected_total{${APP}}[5m]))`, decimals: 0,
    steps: [{ color: 'orange', value: 1 }],
  },
  {
    title: 'Violations de l’invariant',
    description: 'Lignes dont l’état et la zone de stockage divergent. Doit rester à zéro, sous charge comme au repos.',
    expr: `max(praxedo_invariant_violations{${APP}})`, decimals: 0, graph: false,
    steps: [{ color: 'red', value: 1 }],
  },
]);

row('Retard (lag) — comme le retard d’un consommateur sur un broker');
series([
  {
    title: 'Fichiers en attente',
    description: 'Qui grandit sans redescendre : le nœud ne suit plus.',
    targets: [[`sum(praxedo_queue_depth{${APP}})`, 'en attente']],
  },
  {
    title: 'Volume en attente',
    targets: [[`sum(praxedo_queue_depth_bytes{${APP}})`, 'octets en attente']], unit: 'decbytes',
  },
  {
    title: 'Lag par fichier (dépôt → état final)',
    description: 'Centiles du délai de chaque fichier, et âge du plus ancien encore en attente. Ligne rouge : 2 min (SLO-6).',
    targets: [
      [lagQuantile(0.5, '1m'), 'p50'],
      [lagQuantile(0.95, '1m'), 'p95'],
      [lagQuantile(0.99, '1m'), 'p99'],
      [`max(praxedo_queue_oldest_pending_age_seconds{${APP}})`, 'plus ancien en attente'],
    ],
    unit: 's', steps: [{ color: 'red', value: 120 }],
  },
]);

row('Débit — entrant, traité, servi');
series([
  {
    title: 'Débit par la taille',
    description: 'Reçu (dépôts), traité (analysé et mis à disposition ou bloqué), servi (téléchargements). Traité < reçu durablement : le retard grandit.',
    targets: [
      [`sum(rate(praxedo_upload_bytes_total{${APP}}[1m]))`, 'reçu'],
      [throughputBytes, 'traité'],
      [`sum(rate(praxedo_download_bytes_total{${APP}}[1m]))`, 'servi'],
    ],
    unit: 'Bps',
  },
  {
    title: 'Débit en fichiers',
    description: 'Dépôts acceptés par seconde, et fichiers arrivés à leur état final, par issue.',
    targets: [
      [`sum(rate(http_server_requests_seconds_count{${APP},uri="/api/v1/files",method="POST",status="202"}[1m]))`, 'dépôts acceptés'],
      [`sum by (outcome) (rate(praxedo_pipeline_lag_seconds_count{${APP}}[1m]))`, 'terminés — {{outcome}}'],
    ],
    unit: 'suffix: fichiers/s',
  },
]);

row('Ressources du nœud — 1 CPU, 2 Go');
series([
  {
    title: 'Processeur',
    description: 'process_cpu_usage : part du processeur alloué au conteneur, utilisée par la JVM.',
    targets: [[`max(process_cpu_usage{${APP}})`, 'service']], unit: 'percentunit', max: 1,
    steps: [{ color: 'red', value: 0.9 }],
  },
  {
    title: 'Mémoire de la JVM',
    targets: [
      [`sum(jvm_memory_used_bytes{${APP},area="heap"})`, 'tas utilisé'],
      [`sum(jvm_memory_max_bytes{${APP},area="heap"} > 0)`, 'tas maximal'],
      [`sum(jvm_memory_used_bytes{${APP},area="nonheap"})`, 'hors tas'],
      [`sum(jvm_buffer_memory_used_bytes{${APP}})`, 'tampons directs'],
    ],
    unit: 'bytes',
  },
  {
    title: 'Temps passé en GC',
    description: 'Part du temps passée dans les pauses du ramasse-miettes.',
    targets: [[`sum(rate(jvm_gc_pause_seconds_sum{${APP}}[1m]))`, 'pauses GC']], unit: 'percentunit',
    steps: [{ color: 'red', value: 0.1 }],
  },
]);
series([
  {
    title: 'Requêtes HTTP par seconde',
    targets: [[`sum by (status) (rate(http_server_requests_seconds_count{${APP},uri=~"/api/.*"}[1m]))`, '{{status}}']],
    unit: 'reqps', stack: true,
  },
  {
    title: 'Latence HTTP — p95 par route',
    description: 'Le dépôt répond avant l’analyse : sa latence ne doit pas suivre la longueur de la file.',
    targets: [[`histogram_quantile(0.95, sum by (le, method, uri) (rate(http_server_requests_seconds_bucket{${APP},uri=~"/api/.*"}[1m])))`, '{{method}} {{uri}}']],
    unit: 's',
  },
  {
    title: 'Connexions à la base',
    targets: [
      [`sum(hikaricp_connections_active{${APP}})`, 'actives'],
      [`sum(hikaricp_connections_pending{${APP}})`, 'en attente d’une connexion'],
      [`sum(hikaricp_connections_max{${APP}})`, 'maximum du pool'],
    ],
  },
]);

row('Antivirus — la ressource rare');
series([
  {
    title: 'Débit du moteur',
    description: 'Octets lus par le moteur pour rendre ses verdicts, par seconde.',
    targets: [[`sum by (result) (rate(praxedo_scan_bytes_total{${APP}}[1m]))`, '{{result}}']],
    unit: 'Bps', stack: true,
  },
  {
    title: 'Durée d’une analyse — p95',
    targets: [[`histogram_quantile(0.95, sum by (le) (rate(praxedo_scan_duration_seconds_bucket{${APP}}[1m])))`, 'p95']],
    unit: 's',
  },
  {
    title: 'Analyses en cours, disponibilité, pannes',
    description: 'En cours : plafonnées par les boucles d’analyse (4 par nœud). Disponibilité : dernier avis du portillon de santé.',
    targets: [
      [`sum(praxedo_scan_inflight{${APP}})`, 'en cours'],
      [`min(praxedo_antivirus_available{${APP}})`, 'disponible (1/0)'],
      [`sum(rate(praxedo_scan_failures_total{${APP}}[1m])) * 60`, 'pannes / min'],
    ],
  },
]);

const dashboard = {
  uid: 'praxedo-capacite',
  title: 'Praxedo — capacité du service',
  description: 'Retard, débit et ressources d’un nœud : de quoi savoir s’il tient la charge et dimensionner les machines.',
  tags: ['praxedo', 'capacité'],
  timezone: 'browser',
  editable: false,
  graphTooltip: 1,
  refresh: '5s',
  time: { from: 'now-30m', to: 'now' },
  schemaVersion: 41,
  version: 1,
  templating: { list: [] },
  annotations: { list: [] },
  panels,
};

const target = process.argv[2] ?? new URL('./dashboards/praxedo-capacite.json', import.meta.url);
writeFileSync(target, JSON.stringify(dashboard, null, 2) + '\n');
console.log(`${panels.length} panels written`);
