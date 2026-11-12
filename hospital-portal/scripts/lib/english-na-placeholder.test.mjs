// QA D1597 guard (round 44): the admission discharge dialog read "de la chambre
// N/A" in French and "de la habitación N/A" in Spanish. "N/A" is an English
// abbreviation; a missing value is either said in words from the locale files
// ("Aucune chambre attribuée") or shown as a dash ("—", the `dash` pipe).
//
// Rule: no portal template or component source carries the literal `N/A` as a
// string ('N/A', "N/A", `N/A`) or as template text (>N/A<, `|| 'N/A'`).
// Comments are not text on screen and are ignored. Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/** `N/A` quoted, or standing alone between tags. */
const NA_LITERAL = /(['"`])\s*N\/A\s*\1|>\s*N\/A\s*</giu;

/** The source without its comments (`//`, block comments and HTML comments). */
function withoutComments(source, html) {
  const blanked = (m) => m.replace(/[^\n]/g, ' ');
  if (html) return source.replace(/<!--[\s\S]*?-->/g, blanked);
  return source.replace(/\/\*[\s\S]*?\*\//g, blanked).replace(/(^|[^:'"`])\/\/[^\n]*/g, blanked);
}

export function englishPlaceholders(source, html = false) {
  const text = withoutComments(source, html);
  return [...text.matchAll(NA_LITERAL)].map((m) => text.slice(0, m.index).split('\n').length);
}

test('QA D1597: no portal screen shows the English "N/A" for a missing value', () => {
  const found = [];
  for (const file of walk(appDir, ['.html', '.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const lines = englishPlaceholders(readFileSync(file, 'utf8'), file.endsWith('.html'));
    for (const line of lines) found.push(`${rel}:${line}`);
  }
  assert.deepEqual(
    found,
    [],
    'say the missing value with a translated key, or show a dash ("—" / the dash pipe), never "N/A"',
  );
});

test('QA D1597 guard: the scan sees "N/A" on screen, and not in comments', () => {
  assert.deepEqual(englishPlaceholders("<strong>{{ room || 'N/A' }}</strong>", true), [1]);
  assert.deepEqual(englishPlaceholders('<td>N/A</td>', true), [1]);
  assert.deepEqual(englishPlaceholders("\nconst v = x ?? 'N/A';"), [2]);
  assert.deepEqual(englishPlaceholders('const v = x ?? "n/a";'), [1]);
  assert.deepEqual(englishPlaceholders('<!-- never "N/A" here -->', true), []);
  assert.deepEqual(englishPlaceholders("// never 'N/A'\nconst v = '—';"), []);
  assert.deepEqual(englishPlaceholders("const v = 'LAB_OPS.NOT_AVAILABLE';"), []);
});
