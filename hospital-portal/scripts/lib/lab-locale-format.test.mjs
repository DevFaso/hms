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

/**
 * Round 16 siblings: every other template that shows a lab result value (the
 * doctor's results panel, the lab in-basket, the chart review...) formats the
 * value with | labValue and keeps the colon in the translation. Their dates go
 * through locale-aware helpers (formatDate with the reader's locale, or
 * | serverTime | date), so only the value and colon rules apply there.
 */
const resultTemplatesOutsideLab = () =>
  walk(appDir, ['.html']).filter(
    (f) =>
      !relative(appDir, f).replaceAll('\\', '/').startsWith('lab/') &&
      /\.resultValue\b/.test(readFileSync(f, 'utf8')),
  );

test('QA D1002 (siblings): lab values and label colons outside lab/ are formatted for the reader', () => {
  const files = resultTemplatesOutsideLab();
  assert.ok(files.length >= 3, `found ${files.length} templates showing a lab result outside lab/`);
  const offenders = files.flatMap((f) =>
    localeOffenders(readFileSync(f, 'utf8'), relative(appDir, f).replaceAll('\\', '/')).filter(
      (o) => !o.includes(' date without | date'),
    ),
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

/**
 * Round 19 (D1106, D1115, D1116): the D1002 class outside the lab. The chart's
 * vitals tab printed "37.9 °C", the growth table and axis "4.8 kg" / "4.6",
 * the portal dashboard and the proxy viewer "0.7 - 0.99 g/L", the chart's
 * Laboratoire table "6.8" — none had a `.resultValue`, so the scans above
 * never read them. This one reads EVERY template (inline ones too) and every
 * `.ts` for a decimal shown without the shared formatter
 * (shared/i18n/app-locale.ts): rules in lib/decimal-scan.mjs. No baseline.
 */
import { decimalTemplateFindings, decimalCodeFindings } from './decimal-scan.mjs';
import { loadTemplates } from './template-gate.mjs';
import { componentSources } from './component-sources.mjs';

test('QA D1106/D1115/D1116: every decimal on every screen goes through the shared formatter', () => {
  const templates = loadTemplates();
  assert.ok(templates.length > 200, `read ${templates.length} templates`);
  const offenders = [
    ...templates.flatMap((t) =>
      decimalTemplateFindings(t.text, t.componentSource).map(
        (f) => `${t.rel}:${f.line + t.lineOffset} ${f.message}`,
      ),
    ),
    ...componentSources()
      .filter((c) => !c.file.endsWith('app-locale.ts'))
      .flatMap((c) => decimalCodeFindings(c.text).map((f) => `${c.file}:${f.line} ${f.message}`)),
  ];
  assert.deepEqual(offenders, []);
});

test('QA D1106/D1115/D1116 guard: the scan sees the round-19 shapes and passes the fixed ones', () => {
  const rawShapes = [
    '<strong>{{ v.temperatureCelsius }}</strong><small> °C</small>',
    '@for (r of labResults(); track r.id) { <td>{{ r.value || "—" }}</td> }',
    "<td>{{ l.referenceRange || '—' }}</td>",
    '<span>{{ lab.result }}</span>',
    "{{ entry.cervicalDilationCm !== null ? entry.cervicalDilationCm + ' cm' : '—' }}",
    '{{ v.weightKg.toFixed(1) }}',
  ];
  for (const html of rawShapes) {
    assert.ok(decimalTemplateFindings(html).length >= 1, html);
  }
  // The growth table's own helper wrote `${value} ${unit}`: a wrapper is only
  // trusted when its body calls the shared formatter.
  const handMade =
    'formatMeasure(v: number | null, unit: string): string { return `${v} ${unit}`; }';
  const shared =
    'formatMeasure(v: number | null, unit: string): string { return `${formatLabNumber(v)} ${unit}`; }';
  const growthRow = "<td>{{ formatMeasure(p.weightKg, 'kg') }}</td>";
  assert.equal(decimalTemplateFindings(growthRow, handMade).length, 1);
  assert.deepEqual(decimalTemplateFindings(growthRow, shared), []);
  assert.deepEqual(
    decimalTemplateFindings(
      '{{ v.temperatureCelsius | localeNumber }} {{ r.value | labValue: r.unit }} ' +
        "{{ l.referenceRange | labRange }} {{ e.cervicalDilationCm | measure: 'cm' }} " +
        '{{ v.heartRateBpm }} {{ card.value }} {{ s.totalIntakeMl }} {{ p.bmi | number: "1.1-1" }}',
    ),
    [],
  );
  assert.equal(
    decimalCodeFindings('label: (Math.round(value * 10) / 10).toString(),\nconst t = v.toFixed(1);')
      .length,
    2,
  );
  assert.deepEqual(
    decimalCodeFindings('points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`)'),
    [],
  );
});
