const LOCALE = 'fr-FR';

const UNITS = ['octets', 'Ko', 'Mo', 'Go'] as const;

/** 1536 → "1,5 Ko". Binary multiples, French unit names. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes.toLocaleString(LOCALE)} ${bytes > 1 ? 'octets' : 'octet'}`;
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const digits = value >= 100 ? 0 : 1;
  return `${value.toLocaleString(LOCALE, { maximumFractionDigits: digits })} ${UNITS[unit]}`;
}

const dateTimeFormat = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'short', timeStyle: 'short' });
const fullDateTimeFormat = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'long', timeStyle: 'medium' });

/** "25/09/2026 14:32" */
export function formatDateTime(iso: string): string {
  return dateTimeFormat.format(new Date(iso));
}

/** "25 septembre 2026 à 14:32:05" */
export function formatFullDateTime(iso: string): string {
  return fullDateTimeFormat.format(new Date(iso));
}

/** 1250 → "1,3 s" ; 320 → "320 ms" */
export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms.toLocaleString(LOCALE)} ms`;
  return `${(ms / 1000).toLocaleString(LOCALE, { maximumFractionDigits: 1 })} s`;
}

/** 0.4567 → "46 %" */
export function formatPercent(ratio: number): string {
  return ratio.toLocaleString(LOCALE, { style: 'percent', maximumFractionDigits: 0 });
}
