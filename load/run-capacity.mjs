// Cross-platform capacity campaign runner.
//
//   node load/run-capacity.mjs real-antivirus-confirmation
//   node load/run-capacity.mjs profile-real scale-1cpu-8w scale-3nodes
//
// Each argument names load/environments/<variant>.env, the sole source of
// resource limits and load parameters. Several variants run one after the
// other; each starts from fresh volumes, and each but the last is removed once
// its evidence is saved (the containers share fixed names).
//
// Retained under load/results/, per run: k6 summaries, the Prometheus report,
// a docker stats report of every container, run metadata, the logs of every
// node — and, with JFR=true, the node's flight recording and text views of it.
//
// A queue that does not drain after the load stops everything: the files left
// behind are written to <run>-FAILED.md, and the stack is left running for
// diagnosis instead of being removed with its evidence.
import { execFileSync, spawn, spawnSync } from 'node:child_process';
import { closeSync, mkdirSync, openSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { startDockerStats, summarizeDockerStats } from './docker-stats.mjs';
import { pgCounters, startPgActivity, summarizePgActivity } from './pg-activity.mjs';
import { seconds, stageWindows } from './stages.mjs';

const root = resolve(import.meta.dirname, '..');
const resultsDir = resolve(root, 'load/results');
const variants = process.argv.slice(2);
const available = readdirSync(resolve(root, 'load/environments'))
  .filter((name) => name.endsWith('.env')).map((name) => name.slice(0, -4));

if (variants.length === 0 || variants.some((variant) => !available.includes(variant))) {
  console.error(`Usage: node load/run-capacity.mjs <variant> [<variant>...]\nVariants: ${available.join(', ')}`);
  process.exit(2);
}

const EXTRA_NODES = ['backend-2', 'backend-3'];
const NODE_CONTAINERS = ['praxedo-backend', 'praxedo-backend-2', 'praxedo-backend-3'];
const NODE_PORTS = [8080, 8082, 8083];
// Their actuators, on the management port (audit S-08), published on 127.0.0.1 only.
const MANAGEMENT_PORTS = [8091, 8092, 8093];
const NODE_URLS = ['http://backend:8080', 'http://backend-2:8080', 'http://backend-3:8080'];
const JFR_IN_CONTAINER = '/tmp/praxedo.jfr';
const JFR_VIEWS = [
  'container-configuration', 'gc-configuration', 'container-cpu-throttling', 'cpu-load', 'thread-cpu-load',
  'hot-methods', 'socket-reads-by-host', 'socket-writes-by-host', 'contention-by-site', 'pinned-threads',
  'gc-pauses', 'allocation-by-site',
];

mkdirSync(resultsDir, { recursive: true });
for (const [index, variant] of variants.entries()) {
  const last = index === variants.length - 1;
  await campaign(variant, { removeAfter: !last });
}

async function campaign(variant, { removeAfter }) {
  const envFile = `load/environments/${variant}.env`;
  const settings = parseEnv(readFileSync(resolve(root, envFile), 'utf8'));
  const nodes = Number(settings.NODES ?? '1');
  if (!Number.isInteger(nodes) || nodes < 1 || nodes > NODE_PORTS.length) {
    throw new Error(`${envFile}: NODES must be between 1 and ${NODE_PORTS.length}`);
  }
  const jfr = settings.JFR === 'true';
  // BACKEND_JVM_OPTIONS from the environment file (a collector, say), plus
  // the flight recording when asked for: both reach the JVM through
  // JAVA_TOOL_OPTIONS, which it reads by itself.
  const jvmOptions = [
    settings.BACKEND_JVM_OPTIONS,
    jfr ? `-XX:StartFlightRecording=settings=profile,filename=${JFR_IN_CONTAINER},dumponexit=true` : null,
  ].filter(Boolean).join(' ');
  const env = { ...process.env, ...settings, BACKEND_JAVA_TOOL_OPTIONS: jvmOptions };
  const runId = `${new Date().toISOString().replaceAll(':', '-').replace(/\.\d{3}Z$/, 'Z')}-${variant}`;
  const projectName = `praxedo-capacity-${variant}`;
  const files = nodes > 1 ? ['-f', 'docker-compose.yml', '-f', 'load/compose.multinode.yml'] : [];
  const compose = (...args) => ['compose', '--project-name', projectName, ...files, '--env-file', envFile, ...args];
  const extraNodes = EXTRA_NODES.slice(0, nodes - 1);
  const apiTargets = NODE_URLS.slice(0, nodes).join(',');

  console.log(`\n=== Capacity campaign: ${variant} ===`);
  console.log(`Environment: ${envFile}`);
  console.log(`Nodes: ${nodes} × ${settings.BACKEND_CPUS} CPU / ${settings.BACKEND_MEMORY}, `
    + `${settings.WORKER_CONCURRENCY} worker(s) each${jfr ? ', JFR recording on node 1' : ''}`);
  console.log(`Throughput stages: ${settings.LOAD_RATES} files/s for ${settings.LOAD_STEP} each\n`);

  refuseForeignContainers(projectName);
  // Fresh volumes for every campaign: no queue, object or row inherited.
  run(env, 'docker', compose('--profile', 'app', '--profile', 'load', 'down', '-v', '--remove-orphans'));
  run(env, 'docker', compose('--profile', 'app', 'up', '-d', '--build', '--force-recreate',
    'backend', 'keycloak', 'prometheus', 'grafana'));
  await waitForHttp('Node 1', 'http://localhost:8091/actuator/health/readiness', 180_000);
  if (extraNodes.length > 0) {
    // After node 1: its migrations are done, the others start on a ready schema.
    run(env, 'docker', compose('--profile', 'app', 'up', '-d', '--force-recreate', ...extraNodes));
    for (const [offset, port] of MANAGEMENT_PORTS.slice(1, nodes).entries()) {
      await waitForHttp(`Node ${offset + 2}`, `http://localhost:${port}/actuator/health/readiness`, 180_000);
    }
  }
  await waitForHttp('Keycloak', 'http://localhost:9001/health/ready', 180_000);
  await waitForHttp('Prometheus', 'http://localhost:9090/-/ready', 120_000);

  if (settings.ANTIVIRUS_MODE === 'http') {
    await waitForHttp('ClamAV API', 'http://localhost:9000/', 300_000);
  }
  if (settings.ANTIVIRUS_MODE === 'instant-clean') {
    // The backend no longer calls it. Stopping it makes the control run measure
    // the code and its data stores, without an idle ClamAV consuming host RAM.
    run(env, 'docker', compose('stop', 'antivirus'));
  }

  if (!await waitForQueueToDrain()) {
    throw new Error('The campaign requires an empty queue at startup');
  }

  const concurrencySummary = `/load/results/${runId}-concurrency-k6.json`;
  run(env, 'docker', compose('--profile', 'load', 'run', '--rm',
    '-e', `CONCURRENT_UPLOADS=${settings.CONCURRENT_UPLOADS}`,
    '-e', `FILE_SIZE_BYTES=${settings.CONCURRENT_FILE_SIZE_BYTES}`,
    '-e', `API_TARGETS=${apiTargets}`,
    'k6', 'run', '--summary-export', concurrencySummary, '/load/k6/concurrency.js'), [0, 99]);

  if (!await waitForQueueToDrain()) {
    throw new Error('The simultaneous-deposit burst did not drain; throughput stages would not be isolated');
  }

  const statsFile = resolve(resultsDir, `${runId}-docker-stats.jsonl`);
  const stopStats = startDockerStats(projectName, statsFile);
  const pgFile = resolve(resultsDir, `${runId}-pg-activity.jsonl`);
  const pgBefore = pgCounters(env);
  const stopPg = startPgActivity(pgFile, env);
  const throughputStart = new Date().toISOString();
  const throughputSummary = `/load/results/${runId}-throughput-k6.json`;
  // Asynchronous on purpose: the docker stats sampler runs while k6 does.
  const capacityExit = await runAsync(env, 'docker', compose('--profile', 'load', 'run', '--rm',
    '-e', `RATES=${settings.LOAD_RATES}`,
    '-e', `STEP=${settings.LOAD_STEP}`,
    '-e', `WARMUP_RATE=${settings.LOAD_WARMUP_RATE}`,
    '-e', `WARMUP=${settings.LOAD_WARMUP}`,
    '-e', `MAX_VUS=${settings.MAX_VUS ?? '120'}`,
    '-e', `API_TARGETS=${apiTargets}`,
    'k6', 'run', '--summary-export', throughputSummary, '/load/k6/capacity.js'), [0, 99]);
  await stopStats();
  await stopPg();
  const pgAfter = pgCounters(env);

  const prometheusReport = exec(env, 'node', [
    'load/report.mjs', '--start', throughputStart,
    '--rates', settings.LOAD_RATES, '--step', String(seconds(settings.LOAD_STEP)),
    '--warmup', String(seconds(settings.LOAD_WARMUP)),
  ]);
  writeFileSync(resolve(resultsDir, `${runId}-throughput.md`), prometheusReport, 'utf8');
  console.log(prometheusReport);

  const windows = stageWindows({
    start: Math.floor(Date.parse(throughputStart) / 1000),
    rates: settings.LOAD_RATES.split(',').map(Number),
    step: seconds(settings.LOAD_STEP),
    warmup: seconds(settings.LOAD_WARMUP),
  });
  const dependencies = summarizeDockerStats(statsFile, windows, projectName);
  writeFileSync(resolve(resultsDir, `${runId}-dependencies.md`), dependencies, 'utf8');
  console.log(dependencies);

  const queueDrainedAfterThroughput = await waitForQueueToDrain();
  const nodeLogs = saveNodeLogs(runId, nodes);
  const postgresLog = saveContainerLog('praxedo-postgres', `${runId}-postgres.log`, throughputStart);
  const database = await summarizePgActivity({
    file: pgFile,
    windows: [
      { label: 'Échauffement', from: windows[0].from - seconds(settings.LOAD_WARMUP) - 10, to: windows[0].from - 10 },
      ...windows.map((window) => ({ ...window, label: `Palier ${window.rate} fichiers/s` })),
    ],
    before: pgBefore,
    after: pgAfter,
    postgresLog,
    nodeLogs,
  });
  writeFileSync(resolve(resultsDir, `${runId}-database.md`), database, 'utf8');
  const metadata = {
    runId,
    variant,
    environmentFile: envFile,
    settings,
    nodes,
    apiTargets,
    throughputStartedAt: throughputStart,
    throughputExitCode: capacityExit,
    queueDrainedAfterThroughput,
    completedAt: new Date().toISOString(),
    backendInspect: JSON.parse(exec(env, 'docker', ['inspect', 'praxedo-backend'])),
    dockerInfo: JSON.parse(exec(env, 'docker', ['info', '--format', '{{json .}}'])),
    finalMetrics: await metricsSnapshot(),
  };
  writeFileSync(resolve(resultsDir, `${runId}-metadata.json`), `${JSON.stringify(metadata, null, 2)}\n`, 'utf8');

  if (!queueDrainedAfterThroughput) {
    writeFileSync(resolve(resultsDir, `${runId}-FAILED.md`), leftBehind(env, runId, metadata), 'utf8');
    throw new Error(`${variant}: the queue did not drain after the load. Stack left running for diagnosis, `
      + `series stopped; evidence under load/results/${runId}-*`);
  }
  if (jfr) {
    saveFlightRecording(env, runId);
  }
  if (removeAfter) {
    run(env, 'docker', compose('--profile', 'app', '--profile', 'load', 'down', '-v', '--remove-orphans'));
  }
  console.log(`\nResults retained under load/results/ with prefix ${runId}`);
}

/** The recording is written when the JVM exits: stop node 1 gracefully, copy it, render views. */
function saveFlightRecording(env, runId) {
  run(env, 'docker', ['stop', '--time', '90', 'praxedo-backend']);
  const recording = resolve(resultsDir, `${runId}-node1.jfr`);
  run(env, 'docker', ['cp', `praxedo-backend:${JFR_IN_CONTAINER}`, recording]);
  const views = JFR_VIEWS.map((view) => {
    try {
      const text = execFileSync('jfr', ['view', '--width', '200', view, recording],
        { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
      return `## ${view}\n\n${text}`;
    } catch (failure) {
      return `## ${view}\n\nIndisponible : ${failure.message.split('\n')[0]}\n`;
    }
  });
  writeFileSync(resolve(resultsDir, `${runId}-jfr.txt`), views.join('\n'), 'utf8');
}

/** Straight to disk: a node's log runs to several megabytes. */
function saveNodeLogs(runId, nodes) {
  return NODE_CONTAINERS.slice(0, nodes)
    .map((container, index) => saveContainerLog(container, `${runId}-node${index + 1}.log`));
}

function saveContainerLog(container, name, since) {
  const path = resolve(resultsDir, name);
  const file = openSync(path, 'w');
  try {
    spawnSync('docker', ['logs', ...(since ? ['--since', since] : []), container],
      { cwd: root, stdio: ['ignore', file, file] });
  } finally {
    closeSync(file);
  }
  return path;
}

/** What a queue that did not drain left behind, read while the stack still runs. */
function leftBehind(env, runId, metadata) {
  let rows;
  try {
    rows = exec(env, 'docker', ['exec', 'praxedo-postgres', 'psql',
      '-U', env.POSTGRES_USER ?? 'praxedo', '-d', env.POSTGRES_DB ?? 'praxedo', '-c',
      `SELECT id, status, attempts, size_bytes, uploaded_at, status_changed_at, lease_expires_at, lease_holder,
              left(last_error, 120) AS last_error, clock_timestamp() AS db_now
         FROM stored_file
        WHERE status NOT IN ('AVAILABLE', 'INFECTED', 'UNSCANNABLE', 'FAILED_FINAL')
        ORDER BY uploaded_at`]);
  } catch (failure) {
    rows = `Lecture impossible : ${failure.message.split('\n')[0]}`;
  }
  return [
    `# ⚠️ Campagne échouée — \`${metadata.variant}\` (${runId})`,
    '',
    'La file ne s\'est pas vidée dans les 180 s qui suivent la charge. La pile est',
    'restée démarrée pour le diagnostic ; les journaux des nœuds sont dans',
    `\`${runId}-node*.log\`. Ne pas utiliser ces résultats dans une médiane.`,
    '',
    '## Fichiers restés dans la file',
    '',
    '```text',
    rows,
    '```',
    '',
    '## Métriques du nœud 1 à cet instant',
    '',
    '```text',
    ...metadata.finalMetrics,
    '```',
    '',
  ].join('\n');
}

/** Containers have fixed names: another project holding them would make `up` fail obscurely. */
function refuseForeignContainers(projectName) {
  const running = execFileSync('docker', ['ps', '--filter', 'name=praxedo-',
    '--format', '{{.Names}}\t{{.Label "com.docker.compose.project"}}'], { encoding: 'utf8' })
    .split(/\r?\n/).filter(Boolean).map((line) => line.split('\t'))
    .filter(([, project]) => project !== projectName);
  if (running.length > 0) {
    const projects = [...new Set(running.map(([, project]) => project))];
    throw new Error(`Containers of another Compose project are running (${projects.join(', ')}): `
      + `stop it first, e.g. docker compose --project-name ${projects[0]} --profile app --profile load down --remove-orphans`);
  }
}

function parseEnv(source) {
  return Object.fromEntries(source.split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => {
      const separator = line.indexOf('=');
      if (separator < 1) throw new Error(`Invalid environment line: ${line}`);
      return [line.slice(0, separator), line.slice(separator + 1)];
    }));
}

function run(env, command, args, allowed = [0]) {
  const answer = spawnSync(command, args, { cwd: root, env, stdio: 'inherit' });
  if (answer.error) throw answer.error;
  if (!allowed.includes(answer.status)) {
    throw new Error(`${command} ${args.join(' ')} exited with ${answer.status}`);
  }
  return answer.status;
}

function runAsync(env, command, args, allowed = [0]) {
  return new Promise((resolveRun, rejectRun) => {
    const child = spawn(command, args, { cwd: root, env, stdio: 'inherit' });
    child.on('error', rejectRun);
    child.on('exit', (status) => (allowed.includes(status)
      ? resolveRun(status)
      : rejectRun(new Error(`${command} ${args.join(' ')} exited with ${status}`))));
  });
}

function exec(env, command, args) {
  return execFileSync(command, args, { cwd: root, env, encoding: 'utf8' }).trim();
}

async function waitForHttp(name, url, timeout) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try {
      const answer = await fetch(url);
      if (answer.ok) return;
    } catch {
      // Dependency is still starting.
    }
    await delay(2_000);
  }
  throw new Error(`${name} did not become ready within ${timeout / 1_000} seconds`);
}

async function waitForQueueToDrain() {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    try {
      const body = await (await fetch('http://localhost:8091/actuator/prometheus')).text();
      const match = body.match(/^praxedo_queue_depth(?:\{[^}]*\})?\s+([\d.eE+-]+)$/m);
      if (match && Number(match[1]) === 0) return true;
    } catch {
      // Retry while the backend or metrics endpoint is unavailable.
    }
    await delay(2_000);
  }
  return false;
}

async function metricsSnapshot() {
  const body = await (await fetch('http://localhost:8091/actuator/prometheus')).text();
  const wanted = [
    'praxedo_queue_depth',
    'praxedo_queue_depth_bytes',
    'praxedo_invariant_violations',
    'praxedo_admission_rejected_total',
    'praxedo_scan_failures_total',
  ];
  return body.split(/\r?\n/).filter((line) => wanted.some((metric) => line.startsWith(metric)));
}

function delay(milliseconds) {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, milliseconds));
}
