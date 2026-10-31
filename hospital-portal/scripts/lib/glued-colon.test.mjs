// QA D1052 / D1002 guard: a colon typed after a translated label.
//
// "Médicament: …", "Qté en stock: 0", "Ordonnance: …" on the pharmacy screens:
// a template wrote {{ 'X' | translate }}: and French typography writes
// "Médicament :" (a non-breaking space before the colon). The colon belongs to
// the language: {{ 'COMMON.COLON' | translate }} (or a key that carries it).
// lab-locale-format.test.mjs checked this in lab/ only, and round 17 found it
// on the pharmacy screens; this reads every template in the app.
//
// The templates that still glue a colon today are listed in
// glued-colon-baseline.json with how many sites each holds (QA D1052 class,
// #975 reported 125 such sites, not yet fixed). A template not listed fails; a
// listed one fails when it holds more, and when it holds fewer (lower the
// count: the list never outlives what it excused).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const appDir = join(here, '..', '..', 'src', 'app');
const baselinePath = join(here, '..', 'glued-colon-baseline.json');

const GLUED = /\|\s*translate(?:\s*:(?:\{[^}]*\}|[^{}])*)?\s*\}\}\s*:/g;

/** How many glued colons one template text holds. */
export function gluedColons(html) {
  return [...html.matchAll(GLUED)].length;
}

function currentCounts() {
  const counts = {};
  for (const { file, text } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const n = gluedColons(text);
    if (n > 0) counts[rel] = (counts[rel] ?? 0) + n;
  }
  return counts;
}

test('QA D1052: no template glues a colon after a translated label beyond the pinned sites', () => {
  const counts = currentCounts();
  if (process.env.WRITE_GLUED_COLON_BASELINE === '1') {
    const pinned = Object.fromEntries(
      Object.keys(counts)
        .sort()
        .map((k) => [
          k,
          { count: counts[k], why: 'QA D1052 class (#975: reported, not yet fixed)' },
        ]),
    );
    writeFileSync(baselinePath, JSON.stringify(pinned, null, 2) + '\n');
  }
  const pinned = JSON.parse(readFileSync(baselinePath, 'utf8'));
  const problems = [];
  for (const [file, n] of Object.entries(counts)) {
    const pin = pinned[file];
    if (!pin)
      problems.push(
        `${file}: ${n} glued colon(s), not pinned - use {{ 'COMMON.COLON' | translate }}`,
      );
    else if (n > pin.count) problems.push(`${file}: ${n} glued colon(s), pinned ${pin.count}`);
  }
  for (const [file, pin] of Object.entries(pinned)) {
    if (!pin.why || pin.why.startsWith('TODO')) problems.push(`${file}: pin without a reason`);
    const n = counts[file] ?? 0;
    if (n < pin.count)
      problems.push(`${file}: stale pin (pinned ${pin.count}, found ${n}) - lower or delete it`);
  }
  assert.deepEqual(problems, []);
});

test('QA D1052 guard: the scan sees the shapes the defect had', () => {
  assert.equal(gluedColons("<span>{{ 'PHARMACY.MEDICATION' | translate }}:</span>"), 1);
  assert.equal(gluedColons("{{ 'PHARMACY.X' | translate: { n: 1 } }} :"), 1);
  assert.equal(
    gluedColons("{{ 'PHARMACY.MEDICATION' | translate }}{{ 'COMMON.COLON' | translate }}"),
    0,
  );
});
