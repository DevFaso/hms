import { readdirSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Every file under `dir` whose name ends in one of `extensions`, recursively.
 *
 * Was copy-pasted in three gates; `withFileTypes` avoids a `statSync` per
 * entry, which the hand-rolled copies all paid.
 */
export function walk(dir, extensions = ['.html'], out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = resolve(dir, entry.name);
    if (entry.isDirectory()) walk(full, extensions, out);
    else if (extensions.some((ext) => entry.name.endsWith(ext))) out.push(full);
  }
  return out;
}
