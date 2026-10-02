// Où tombe chaque palier dans le temps — une seule définition, partagée par la
// lecture de Prometheus (report.mjs) et celle des relevés Docker
// (docker-stats.mjs). Elle doit rester alignée sur les étapes de k6/capacity.js :
// échauffement, puis pour chaque palier 10 s de montée et STEP au débit visé.

export const RAMP_SECONDS = 10;

/** Fenêtres de mesure, en secondes epoch : la montée est exclue. */
export function stageWindows({ start, rates, step, warmup }) {
  return rates.map((rate, index) => {
    const from = start + warmup + index * (RAMP_SECONDS + step) + RAMP_SECONDS;
    return { rate, from, to: from + step };
  });
}

/** "60s" ou "60" → 60. */
export function seconds(value) {
  return Number(String(value).replace(/s$/, ''));
}
