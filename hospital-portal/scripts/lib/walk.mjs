import { readdirSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Every file under `dir` whose name ends in one of `extensions`, recursively.
 *
 * check-i18n-referenced-keys.mjs still has its own `readdirSync` + `statSync`
 * copy; folding it in belongs in the same pass that widens the lint glob over
 * scripts/, so for now this is the newer of two implementations rather than the
 * consolidation it ought to be. `withFileTypes` avoids a `statSync` per entry.
 */
export function walk(dir, extensions = ['.html'], out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = resolve(dir, entry.name);
    if (entry.isDirectory()) walk(full, extensions, out);
    else if (extensions.some((ext) => entry.name.endsWith(ext))) out.push(full);
  }
  return out;
}
