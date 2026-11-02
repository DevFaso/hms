// QA D1340 (round 29, user decision): a date-time on screen never shows
// seconds — "2 nov. 2026, 15:00", not "2 nov. 2026, 15:00:00".
//
// The labour card's delivery time read "15:00:00" through `date: 'medium'`,
// and the same pipe sat in some ninety templates. The way out is the
// `dateTime` pipe (shared/pipes/date-time.pipe.ts: the locale's medium date
// and short time). This reads every template (each `.html` and each inline
// `template:`) and every non-spec `.ts` for the shapes that print seconds:
//
// - rule `pipe`: `| date: 'medium'` (and `'long'`, `'full'`, `'mediumTime'`,
//   `'longTime'`, `'fullTime'`), or a pattern naming seconds (`ss`, `:s`);
// - rule `format`: `formatDate(…, 'medium' | 'long' | … | a pattern with ss)`
//   in component code;
// - rule `locale-string`: `toLocaleString()` / `toLocaleTimeString()` with no
//   options (the JavaScript default prints seconds), or `Intl` options asking
//   for `second` or a `timeStyle` other than `'short'`.
//
// Today's sites outside this branch's directories are pinned with the branch
// that owns them; a pin only shrinks (a stale pin fails).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { walk } from './walk.mjs';
import { balanced } from './js-scan.mjs';

/** Whether an argument list has a second argument (a comma outside brackets). */
function hasTopLevelComma(args) {
  let depth = 0;
  for (const ch of args) {
    if ('([{'.includes(ch)) depth++;
    else if (')]}'.includes(ch)) depth--;
    else if (ch === ',' && depth === 0) return true;
  }
  return false;
}

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const rel = (file) => relative(appDir, file).split('\\').join('/');

const NAMED_WITH_SECONDS = new Set([
  'medium',
  'long',
  'full',
  'mediumTime',
  'longTime',
  'fullTime',
]);
const withSeconds = (format) =>
  NAMED_WITH_SECONDS.has(format) || /(?:^|[^s])ss?(?![a-z])|:s/.test(format);

/** The seconds-printing date pipes of one template. */
export function secondsInTemplate(html) {
  const out = [];
  for (const m of html.matchAll(/\|\s*date\s*:\s*'([^']*)'/g)) {
    if (withSeconds(m[1])) out.push(`pipe:${m[1]}`);
  }
  return out;
}

/** The seconds-printing date formatting of one component source. */
export function secondsInSource(source) {
  const out = [];
  for (const m of source.matchAll(/\bformatDate\(\s*[^,]+,\s*'([^']*)'/g)) {
    if (withSeconds(m[1])) out.push(`format:${m[1]}`);
  }
  for (const m of source.matchAll(/\.(toLocaleString|toLocaleTimeString)\(/g)) {
    // No options (nothing, or a locale alone): the JavaScript default prints seconds.
    const args = balanced(source, m.index + m[0].length - 1).slice(1, -1);
    if (!hasTopLevelComma(args)) {
      out.push(`locale-string:${m[1]}(${args.trim() === '' ? '' : 'locale'})`);
    }
  }
  for (const m of source.matchAll(/\bsecond\s*:\s*'(?:numeric|2-digit)'/g)) {
    out.push(`locale-string:${m[0].replace(/\s+/g, '')}`);
  }
  for (const m of source.matchAll(/\btimeStyle\s*:\s*'(medium|long|full)'/g)) {
    out.push(`locale-string:timeStyle:${m[1]}`);
  }
  return out;
}

/**
 * `file` → how many seconds-printing sites it may still hold, and why: the
 * front-office directories belong to fix/r29-portal-front-office, which owns
 * their D1340 sweep in round 29 (delete each pin when that branch lands).
 */
const FRONT_OFFICE =
  'D1340: front-office directory, swept by fix/r29-portal-front-office (round 29)';
const PINNED = new Map([
  ['appointments/appointment-detail.html', [2, FRONT_OFFICE]],
  ['patient-portal/my-education/my-education.component.html', [1, FRONT_OFFICE]],
  ['patient-portal/my-lab-results/my-lab-results.component.html', [1, FRONT_OFFICE]],
  ['patient-portal/my-notifications/my-notifications.component.html', [1, FRONT_OFFICE]],
  ['patient-portal/my-screenings/my-screenings.component.html', [1, FRONT_OFFICE]],
  ['patient-portal/my-sharing/my-sharing.component.html', [1, FRONT_OFFICE]],
  ['patients/chart-review/chart-review.component.html', [3, FRONT_OFFICE]],
  ['patients/coverage-tab/coverage-tab.component.html', [1, FRONT_OFFICE]],
  ['patients/documents-tab/documents-tab.component.html', [1, FRONT_OFFICE]],
  ['patients/patient-chart/patient-chart.component.html', [1, FRONT_OFFICE]],
  ['patients/patient-detail.html', [1, FRONT_OFFICE]],
  ['pharmacy/stock-adjustment.html', [1, FRONT_OFFICE]],
  // Not a date: the decimal-separator probe `(1.5).toLocaleString(locale)`.
  [
    'shared/i18n/app-locale.ts',
    [1, 'a number formatted to read the decimal separator, not a date'],
  ],
]);

test('QA D1340: no date-time on screen shows seconds', () => {
  const counts = new Map();
  const where = [];
  const add = (file, hits) => {
    if (hits.length === 0) return;
    counts.set(file, (counts.get(file) ?? 0) + hits.length);
    for (const h of hits) where.push(`${file} ${h}`);
  };
  for (const { file, text } of templatesIn(appDir)) add(rel(file), secondsInTemplate(text));
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    add(rel(file), secondsInSource(readFileSync(file, 'utf8')));
  }
  const over = where.filter((w) => {
    const file = w.split(' ')[0];
    return (counts.get(file) ?? 0) > (PINNED.get(file)?.[0] ?? 0);
  });
  assert.deepEqual(
    over,
    [],
    'a date-time with seconds: use the dateTime pipe (shared/pipes/date-time.pipe.ts)',
  );
  const stale = [...PINNED].filter(([file, [n]]) => (counts.get(file) ?? 0) < n).map(([f]) => f);
  assert.deepEqual(stale, [], 'the site was fixed: lower or delete its pin');
});

test('QA D1340 guard: the scan sees the shapes that print seconds, and passes the ones that do not', () => {
  assert.deepEqual(secondsInTemplate("{{ d.birthDateTime | serverTime | date: 'medium' }}"), [
    'pipe:medium',
  ]);
  assert.deepEqual(secondsInTemplate("{{ x | date: 'HH:mm:ss' }}"), ['pipe:HH:mm:ss']);
  assert.deepEqual(
    secondsInTemplate(
      "{{ a | date: 'short' }} {{ b | date: 'mediumDate' }} {{ c | date: 'shortTime' }} {{ d | dateTime }} {{ e | date: 'MMM yyyy' }}",
    ),
    [],
  );
  assert.deepEqual(secondsInSource("formatDate(v, 'medium', locale)"), ['format:medium']);
  assert.deepEqual(secondsInSource('d.toLocaleString()'), ['locale-string:toLocaleString()']);
  assert.deepEqual(secondsInSource('d.toLocaleTimeString(currentLocale())'), [
    'locale-string:toLocaleTimeString(locale)',
  ]);
  assert.deepEqual(secondsInSource("{ hour: 'numeric', second: '2-digit' }"), [
    "locale-string:second:'2-digit'",
  ]);
  assert.deepEqual(
    secondsInSource(
      "d.toLocaleString(currentLocale(), { month: 'short', hour: 'numeric', minute: '2-digit' }); formatDate(v, 'shortTime', l); (1.5).toLocaleString(locale)",
    ),
    ['locale-string:toLocaleString(locale)'],
  );
});
