import { z } from 'zod';

export const UNKNOWN = 'UNKNOWN' as const;

/**
 * Every enumeration of the contract is declared extensible. Parsing must
 * therefore TOLERATE unknown values: a value added by the server maps to
 * `UNKNOWN` (neutral display) instead of breaking the screen (rule F-2).
 * `null` and missing values still fail.
 */
export function tolerantEnum<const T extends readonly [string, ...string[]]>(values: T) {
  return z.string().pipe(z.enum([...values, UNKNOWN]).catch(UNKNOWN));
}
