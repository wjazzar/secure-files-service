// Relevé de chaque conteneur de la campagne pendant les paliers : processeur,
// mémoire, réseau. Prometheus ne voit que le service ; ce relevé voit ses
// dépendances (antivirus, base, stockage objet) et le générateur de charge.
//
// `docker stats --no-stream` toutes les 5 s environ : un relevé coûte ~2 s, et
// un relevé perdu n'invalide pas la campagne. Le CPU est exprimé en cœurs
// (100 % = un cœur) : c'est l'unité du dimensionnement.
import { execFile } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { promisify } from 'node:util';

const exec = promisify(execFile);
const INTERVAL_MS = 5_000;

/** Démarre le relevé ; la fonction rendue l'arrête et attend le dernier échantillon. */
export function startDockerStats(project, file) {
  let stopped = false;
  const sampling = (async () => {
    while (!stopped) {
      const began = Date.now();
      try {
        const { stdout: ids } = await exec('docker',
          ['ps', '-q', '--filter', `label=com.docker.compose.project=${project}`]);
        const containers = ids.split(/\s+/).filter(Boolean);
        if (containers.length > 0) {
          const { stdout } = await exec('docker', ['stats', '--no-stream', '--format', '{{json .}}', ...containers]);
          const t = began / 1000;
          for (const line of stdout.split(/\r?\n/).filter(Boolean)) {
            appendFileSync(file, `${JSON.stringify({ t, ...JSON.parse(line) })}\n`);
          }
        }
      } catch {
        // Un échantillon manqué : le suivant suffit.
      }
      await new Promise((done) => setTimeout(done, Math.max(0, INTERVAL_MS - (Date.now() - began))));
    }
  })();
  return async () => {
    stopped = true;
    await sampling;
  };
}

const UNITS = {
  B: 1, kB: 1e3, KB: 1e3, MB: 1e6, GB: 1e9, TB: 1e12,
  KiB: 2 ** 10, MiB: 2 ** 20, GiB: 2 ** 30, TiB: 2 ** 40,
};

function bytes(text) {
  const match = /([\d.]+)\s*([kKMGT]i?B|B)/.exec(text ?? '');
  return match ? Number(match[1]) * UNITS[match[2]] : NaN;
}

/** "rx / tx" → rx + tx, en octets. */
function transferred(text) {
  return (text ?? '').split('/').map(bytes).reduce((sum, value) => sum + value, 0);
}

/** Un tableau Markdown par palier : cœurs moyens, mémoire maximale, réseau. */
export function summarizeDockerStats(file, windows, project) {
  let samples;
  try {
    samples = readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line));
  } catch {
    return 'Aucun relevé docker stats.\n';
  }
  const shortName = (name) => name.replace(`${project}-`, '');
  const lines = [
    'Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; '
      + 'réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).',
    '',
  ];
  for (const window of windows) {
    const inWindow = samples.filter((sample) => sample.t >= window.from && sample.t <= window.to);
    const byContainer = Map.groupBy(inWindow, (sample) => shortName(sample.Name));
    const rows = [...byContainer].map(([name, series]) => {
      const cpu = series.reduce((sum, s) => sum + Number.parseFloat(s.CPUPerc), 0) / series.length / 100;
      const memory = Math.max(...series.map((s) => bytes(s.MemUsage.split('/')[0])));
      const first = series[0];
      const last = series.at(-1);
      const net = last.t > first.t ? (transferred(last.NetIO) - transferred(first.NetIO)) / (last.t - first.t) / 1e6 : NaN;
      return { name, cpu, memory, net, count: series.length };
    }).sort((a, b) => b.cpu - a.cpu);

    lines.push(`### Palier ${window.rate} fichiers/s`, '');
    lines.push('| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |');
    lines.push('|---|---:|---:|---:|---:|');
    for (const row of rows) {
      lines.push(`| ${row.name} | ${row.cpu.toFixed(2)} | ${(row.memory / 2 ** 20).toFixed(0)} `
        + `| ${Number.isFinite(row.net) ? row.net.toFixed(1) : '—'} | ${row.count} |`);
    }
    lines.push('');
  }
  return `${lines.join('\n')}\n`;
}
