// QA D1002 guard: the laboratory screens format for the reader's language.
//
// /lab-results showed "0.32g/L" (an English decimal point, the unit glued to
// the value), the MTM review showed "2026-11-10", and labels were followed by
// a colon typed in the template ("Résultat:"), which French typography writes
// "Résultat :". This reads every lab template (and the MTM review, which shows
// the same kind of data) and fails on any of the three shapes coming back:
//   - a lab value (resultValue, quantity) interpolated without | labValue;
//   - a date field (…Date, …At, …Datetime) interpolated without | date;
//   - a colon glued after a translated label: {{ 'X' | translate }}: — the
//     colon belongs in the translation (COMMON.LABEL_VALUE, or a *_PREFIX key).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const templates = () => [
  ...walk(join(appDir, 'lab'), ['.html']),
  join(appDir, 'pharmacy', 'mtm-review.html'),
];

const LAB_VALUE = /\.(resultValue|quantity)\b/;
const DATE_FIELD = /\.[a-zA-Z]*(Date|At|Datetime|DateTime)\b/;

/** The offenders in one template's text. */
export function localeOffenders(html, file = 'template') {
  const offenders = [];
  const at = (index) => `${file}:${html.slice(0, index).split('\n').length}`;
  for (const m of html.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
    const expr = m[1];
    if (LAB_VALUE.test(expr) && !/\|\s*labValue\b/.test(expr)) {
      offenders.push(`${at(m.index)} lab value without | labValue: ${expr.trim()}`);
    }
    if (DATE_FIELD.test(expr) && !/\|\s*date\b/.test(expr)) {
      offenders.push(`${at(m.index)} date without | date: ${expr.trim()}`);
    }
  }
  for (const m of html.matchAll(/\|\s*translate\s*\}\}\s*:/g)) {
    offenders.push(`${at(m.index)} colon glued after a translated label`);
  }
  return offenders;
}

test('QA D1002: lab values, dates and labels are formatted for the reader', () => {
  const files = templates();
  assert.ok(files.length >= 10);
  const offenders = files.flatMap((f) =>
    localeOffenders(readFileSync(f, 'utf8'), relative(appDir, f).replaceAll('\\', '/')),
  );
  assert.deepEqual(offenders, []);
});

test('QA D1002 guard: the scan sees the shapes the defect had', () => {
  assert.equal(localeOffenders('<strong>{{ r.resultValue }}</strong>').length, 1);
  assert.equal(localeOffenders('<td>{{ r.followUpDate || "—" }}</td>').length, 1);
  assert.equal(localeOffenders("{{ 'PORTAL.RESULT' | translate }}: <b>x</b>").length, 1);
  assert.deepEqual(
    localeOffenders(
      "{{ r.resultValue | labValue: r.resultUnit }} {{ r.followUpDate | date: 'mediumDate' }}",
    ),
    [],
  );
});
