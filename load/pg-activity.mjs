// Ce que la base attend pendant une campagne — pour savoir, quand les
// connexions se figent, si c'est un verrou, le disque, ou l'application qui
// garde sa transaction ouverte.
//
// Toutes les 2 s, pg_stat_activity : chaque session non inactive, son pool
// (praxedo-api, praxedo-queue), son état, ce qu'elle attend, depuis combien de
// temps, et qui la bloque. Avant et après la charge, les compteurs de
// pg_stat_wal et pg_stat_io donnent le temps moyen d'un fsync.
//
//   wait = Lock:…                          un verrou ; blocked_by dit qui le tient
//   wait = IO:… (WalSync, DataFileWrite…)  le disque
//   state = idle in transaction, ClientRead l'application garde la transaction
import { execFile, execFileSync } from 'node:child_process';
import { appendFileSync, createReadStream, readFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';

const exec = promisify(execFile);
const INTERVAL_MS = 2_000;
const STALL_SESSIONS = 5;
const STALL_SECONDS = 2;

const SAMPLE = `
SELECT json_build_object(
  't', extract(epoch FROM clock_timestamp()),
  'sessions', coalesce((SELECT json_agg(json_build_object(
      'pid', pid, 'app', coalesce(nullif(application_name, ''), backend_type), 'db', datname,
      'client', host(client_addr), 'state', state,
      'wait', coalesce(wait_event_type || ':' || wait_event, ''),
      'age', round(extract(epoch FROM clock_timestamp()
                   - CASE WHEN state = 'active' THEN query_start ELSE xact_start END)::numeric, 2),
      'blocked_by', pg_blocking_pids(pid),
      'query', left(regexp_replace(query, '\\s+', ' ', 'g'), 110)))
    FROM pg_stat_activity
   WHERE backend_type IN ('client backend', 'autovacuum worker')
     AND pid <> pg_backend_pid()
     AND state IS DISTINCT FROM 'idle'), '[]'),
  'idle', (SELECT coalesce(json_object_agg(app, n), '{}') FROM (
      SELECT application_name AS app, count(*) AS n FROM pg_stat_activity
       WHERE backend_type = 'client backend' AND state = 'idle' GROUP BY 1) idle))`;

const COUNTERS = `
SELECT json_build_object(
  'wal', (SELECT row_to_json(w) FROM (SELECT wal_records, wal_bytes, wal_write, wal_sync,
                                              wal_write_time, wal_sync_time FROM pg_stat_wal) w),
  'io', (SELECT json_agg(row_to_json(i)) FROM (
           SELECT backend_type, object, context, writes, write_time, fsyncs, fsync_time
             FROM pg_stat_io WHERE coalesce(writes, 0) > 0 OR coalesce(fsyncs, 0) > 0) i),
  'db', (SELECT row_to_json(d) FROM (SELECT xact_commit, deadlocks, blk_write_time
                                       FROM pg_stat_database WHERE datname = current_database()) d))`;

function psqlArgs(env, sql) {
  return ['exec', 'praxedo-postgres', 'psql', '-U', env.POSTGRES_USER ?? 'praxedo',
    '-d', env.POSTGRES_DB ?? 'praxedo', '-At', '-c', sql];
}

/** Démarre le relevé ; la fonction rendue l'arrête. Un relevé qui échoue ou tarde est lui-même un signal. */
export function startPgActivity(file, env) {
  let stopped = false;
  const sampling = (async () => {
    while (!stopped) {
      const began = Date.now();
      try {
        const { stdout } = await exec('docker', psqlArgs(env, SAMPLE), { timeout: 10_000, maxBuffer: 16 * 1024 * 1024 });
        const sample = JSON.parse(stdout.trim());
        sample.took = (Date.now() - began) / 1000;
        appendFileSync(file, `${JSON.stringify(sample)}\n`);
      } catch (failure) {
        appendFileSync(file, `${JSON.stringify({ t: began / 1000, error: failure.killed ? 'timeout (10 s)'
          : String(failure.stderr || failure.message).split('\n')[0] })}\n`);
      }
      await new Promise((done) => setTimeout(done, Math.max(0, INTERVAL_MS - (Date.now() - began))));
    }
  })();
  return async () => {
    stopped = true;
    await sampling;
  };
}

/** Compteurs cumulés de la base : à relever avant et après la charge. */
export function pgCounters(env) {
  try {
    return JSON.parse(execFileSync('docker', psqlArgs(env, COUNTERS), { encoding: 'utf8', timeout: 20_000 }).trim());
  } catch {
    return null;
  }
}

const hms = (t) => new Date(t * 1000).toISOString().slice(11, 19);
const ms = (value) => (Number.isFinite(value) ? value.toFixed(2) : '—');

/** Le rapport : sessions par palier, blocages, disque, verrous tenus trop longtemps. */
export async function summarizePgActivity({ file, windows, before, after, postgresLog, nodeLogs }) {
  let samples = [];
  try {
    samples = readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line));
  } catch {
    // no samples: said below
  }
  const lines = ['# Base de données pendant la charge', ''];
  lines.push(`${samples.length} relevés de \`pg_stat_activity\` toutes les 2 s ; `
    + `${samples.filter((s) => s.error).length} en échec ou hors délai.`, '');

  lines.push('## Disque', '', ...disk(before, after), '');

  lines.push('## Sessions non inactives, par palier', '',
    'Nombre moyen (et maximal) de sessions dans chaque situation, par relevé. `age` : depuis quand '
      + 'la requête tourne (session active) ou la transaction est ouverte (sinon).', '');
  for (const window of windows) {
    const inWindow = samples.filter((s) => !s.error && s.t >= window.from && s.t <= window.to);
    lines.push(`### ${window.label}`, '');
    if (inWindow.length === 0) {
      lines.push('Aucun relevé.', '');
      continue;
    }
    const tally = new Map();
    for (const sample of inWindow) {
      const perSample = new Map();
      for (const s of sample.sessions) {
        const key = `${s.app} | ${s.state} | ${s.wait || '—'}`;
        perSample.set(key, (perSample.get(key) ?? 0) + 1);
      }
      for (const [key, n] of perSample) {
        const t = tally.get(key) ?? { sum: 0, max: 0 };
        t.sum += n;
        t.max = Math.max(t.max, n);
        tally.set(key, t);
      }
    }
    lines.push('| Pool | État | Attente | Moyenne | Max |', '|---|---|---|---:|---:|');
    for (const [key, t] of [...tally].sort((a, b) => b[1].sum - a[1].sum).slice(0, 12)) {
      lines.push(`| ${key} | ${(t.sum / inWindow.length).toFixed(2)} | ${t.max} |`);
    }
    const idle = inWindow.at(-1).idle ?? {};
    lines.push('', `Inactives au dernier relevé : ${Object.entries(idle).map(([app, n]) => `${app || '?'} ${n}`).join(', ') || 'aucune'}.`, '');
  }

  lines.push('## Blocages', '',
    `Relevés où au moins ${STALL_SESSIONS} sessions praxedo sont engagées depuis plus de ${STALL_SECONDS} s.`, '');
  const stalls = samples.filter((s) => !s.error
    && s.sessions.filter((x) => String(x.app).startsWith('praxedo') && x.age >= STALL_SECONDS).length >= STALL_SESSIONS);
  if (stalls.length === 0) {
    lines.push('Aucun.', '');
  } else {
    lines.push('| Heure | Sessions | Attentes | Bloquées par | Exemple de requête |', '|---|---:|---|---|---|');
    for (const stall of stalls.slice(0, 40)) {
      const stuck = stall.sessions.filter((x) => String(x.app).startsWith('praxedo') && x.age >= STALL_SECONDS);
      const waits = countBy(stuck, (x) => `${x.state}/${x.wait || '—'}`);
      const blockers = [...new Set(stuck.flatMap((x) => x.blocked_by ?? []))];
      const blockerInfo = blockers.map((pid) => {
        const holder = stall.sessions.find((x) => x.pid === pid);
        return holder ? `${pid} (${holder.app}, ${holder.state}, ${holder.wait || '—'})` : String(pid);
      });
      lines.push(`| ${hms(stall.t)} | ${stuck.length} | ${waits} | ${blockerInfo.join('; ') || '—'} `
        + `| \`${(stuck[0]?.query ?? '').replaceAll('|', '/')}\` |`);
    }
    lines.push('');
  }
  const failures = samples.filter((s) => s.error);
  if (failures.length > 0) {
    lines.push('Relevés en échec : ', ...failures.slice(0, 10).map((f) => `- ${hms(f.t)} : ${f.error}`), '');
  }
  const slow = samples.filter((s) => !s.error && s.took > 1);
  if (slow.length > 0) {
    lines.push(`Relevés lents (> 1 s, la base répondait mal) : ${slow.map((s) => `${hms(s.t)} ${s.took.toFixed(1)} s`).join(', ')}.`, '');
  }

  lines.push('## Journal de PostgreSQL', '', ...postgresLogSummary(postgresLog), '');
  lines.push('## Connexions gardées plus de 2 s (détection de fuite Hikari)', '', ...(await leaks(nodeLogs)), '');
  return `${lines.join('\n')}\n`;
}

function disk(before, after) {
  if (!before?.wal || !after?.wal) {
    return ['Compteurs indisponibles.'];
  }
  const delta = (key) => Number(after.wal[key]) - Number(before.wal[key]);
  const syncs = delta('wal_sync');
  const rows = [
    `- journal (WAL) : ${delta('wal_records')} enregistrements, ${(delta('wal_bytes') / 1e6).toFixed(0)} Mo, `
      + `${syncs} fsync ; **fsync moyen ${ms(delta('wal_sync_time') / syncs)} ms**, `
      + `écriture moyenne ${ms(delta('wal_write_time') / delta('wal_write'))} ms ;`,
  ];
  if (before.db && after.db) {
    rows.push(`- transactions validées : ${Number(after.db.xact_commit) - Number(before.db.xact_commit)} ; `
      + `interblocages : ${Number(after.db.deadlocks) - Number(before.db.deadlocks)} ;`);
  }
  const key = (i) => `${i.backend_type}/${i.object}/${i.context}`;
  const previous = new Map((before.io ?? []).map((i) => [key(i), i]));
  for (const io of after.io ?? []) {
    const p = previous.get(key(io)) ?? {};
    const fsyncs = Number(io.fsyncs ?? 0) - Number(p.fsyncs ?? 0);
    const writes = Number(io.writes ?? 0) - Number(p.writes ?? 0);
    if (fsyncs > 0 || writes > 0) {
      rows.push(`- ${key(io)} : ${writes} écritures (${ms((Number(io.write_time ?? 0) - Number(p.write_time ?? 0)) / Math.max(writes, 1))} ms), `
        + `${fsyncs} fsync (${ms((Number(io.fsync_time ?? 0) - Number(p.fsync_time ?? 0)) / Math.max(fsyncs, 1))} ms)`);
    }
  }
  return rows;
}

function postgresLogSummary(file) {
  let text;
  try {
    text = readFileSync(file, 'utf8');
  } catch {
    return ['Journal indisponible.'];
  }
  const patterns = [
    ['attentes de verrou', /still waiting for|acquired .* after/],
    ['instructions > 1 s', /duration: \d/],
    ['autovacuum', /automatic (vacuum|analyze)/],
    ['checkpoints', /checkpoint (starting|complete)/],
    ['erreurs', /\b(ERROR|FATAL):/],
  ];
  const all = text.split(/\r?\n/);
  const rows = [];
  for (const [label, pattern] of patterns) {
    const found = all.filter((line) => pattern.test(line));
    rows.push(`- ${label} : ${found.length}`);
    for (const line of found.slice(0, 5)) {
      rows.push(`  - \`${line.slice(0, 220).replaceAll('`', "'")}\``);
    }
  }
  return rows;
}

/** Où les connexions retenues ont été prises : le premier cadre du service sous l'alerte de Hikari. */
async function leaks(nodeLogs) {
  const sites = new Map();
  let total = 0;
  for (const file of nodeLogs) {
    let pending = null;
    let depth = 0;
    try {
      for await (const line of createInterface({ input: createReadStream(file) })) {
        const alert = /Connection leak detection triggered for .* on thread ([^,]+)/.exec(line);
        if (alert) {
          total += 1;
          pending = alert[1].replace(/-\d+$/, '-N');
          depth = 0;
          continue;
        }
        if (pending !== null) {
          depth += 1;
          const frame = /^\s+at (com\.praxedo\.[^(]+)\(([^)]+)\)/.exec(line);
          if (frame || depth > 60) {
            const key = `${pending} ← ${frame ? `${frame[1]} (${frame[2]})` : 'hors du service'}`;
            sites.set(key, (sites.get(key) ?? 0) + 1);
            pending = null;
          }
        }
      }
    } catch {
      // missing log: counted as nothing
    }
  }
  if (total === 0) {
    return ['Aucune (ou détection désactivée : `-Dspring.datasource.hikari.leak-detection-threshold`).'];
  }
  return [`${total} alertes. Par fil d'exécution et lieu de prise :`, '',
    ...[...sites].sort((a, b) => b[1] - a[1]).slice(0, 12).map(([key, n]) => `- ${n} × ${key}`)];
}

function countBy(items, key) {
  const counts = new Map();
  for (const item of items) counts.set(key(item), (counts.get(key(item)) ?? 0) + 1);
  return [...counts].sort((a, b) => b[1] - a[1]).map(([k, n]) => `${n} ${k}`).join(', ');
}
