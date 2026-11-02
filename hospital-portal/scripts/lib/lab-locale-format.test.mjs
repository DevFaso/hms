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
  // A sibling in a folder another round-21 branch owns, pinned by its exact
  // message so the merge that fixes it must delete it (a stale pin fails).
  const pinned = new Map();
  assert.deepEqual(
    offenders.filter((o) => !pinned.has(o)),
    [],
  );
  assert.deepEqual(
    [...pinned.keys()].filter((p) => !offenders.includes(p)),
    [],
    'a pinned sibling is fixed: delete its pin',
  );
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

/**
 * QA D1095 (round 19): a lab value is never shown without its unit. The
 * worklist read "Glycémie à jeun = 0,40", the doctor's critical list "2,4"
 * and the portal "Résultat : 2,5" while ranges existed in both g/L and mmol/L.
 * Every interpolation of a lab result value — `.resultValue`, the portal's
 * `lab.result` / `displayResult(...)` — must carry its unit: `| labValue:` with
 * a unit argument, the unit in the same expression, or a unit field printed
 * right after it (within the same cell).
 */
// The server writes a reference range with its unit ("3,5 - 5 mmol/L"), so
// `displayResult(lab.referenceRange)` is not a bare value.
const RESULT_VALUE = /\.resultValue\b|\b(lab|l)\.result\b/;
/** An interpolation that opens an element styled as a lab value (`class="lab-value"`). */
const LAB_VALUE_ELEMENT = /class="[^"]*\blab-value\b[^"]*"[^>]*>\s*$/;
const UNIT_NEARBY = /\b(resultUnit|unit)\b/;

export function unitOffenders(html, file = 'template') {
  const offenders = [];
  const at = (index) => `${file}:${html.slice(0, index).split('\n').length}`;
  for (const m of html.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
    const expr = m[1];
    const before = html.slice(Math.max(0, m.index - 200), m.index);
    const labValueElement = LAB_VALUE_ELEMENT.test(before) && !/\|\s*translate\b/.test(expr);
    if (!RESULT_VALUE.test(expr) && !labValueElement) continue;
    if (/\|\s*labValue\s*:/.test(expr) || UNIT_NEARBY.test(expr)) continue;
    // A unit printed right after the value, before the cell or element closes.
    const after = html.slice(m.index + m[0].length, m.index + m[0].length + 400);
    const cellEnd = after.search(/<\/(td|strong|dd|li|p)>/);
    const window = cellEnd >= 0 ? after.slice(0, cellEnd) : after;
    if (UNIT_NEARBY.test(window)) continue;
    offenders.push(`${at(m.index)} lab value without its unit: ${expr.trim()}`);
  }
  return offenders;
}

test('QA D1095: every template shows a lab value with its unit', () => {
  const files = walk(appDir, ['.html']).filter((f) => {
    const html = readFileSync(f, 'utf8');
    return RESULT_VALUE.test(html) || /\blab-value\b/.test(html);
  });
  assert.ok(files.length >= 8, `found ${files.length} templates showing a lab value`);
  const offenders = files.flatMap((f) =>
    unitOffenders(readFileSync(f, 'utf8'), relative(appDir, f).replaceAll('\\', '/')),
  );
  assert.deepEqual(offenders, []);
});

test('QA D1095 guard: the scan sees a value shown without its unit', () => {
  assert.equal(unitOffenders('<td>{{ r.resultValue | labValue }}</td>').length, 1);
  assert.equal(unitOffenders('<strong>{{ displayResult(lab.result) }}</strong>').length, 1);
  assert.equal(unitOffenders('<span class="lab-value">{{ x.value }}</span></td>').length, 1);
  assert.deepEqual(unitOffenders('<td>{{ r.resultValue | labValue: r.resultUnit }}</td>'), []);
  assert.deepEqual(
    unitOffenders('<td>{{ r.resultValue | labValue }} {{ r.resultUnit || "—" }}</td>'),
    [],
  );
  assert.deepEqual(
    unitOffenders(
      '<strong>{{ displayResult(lab.result) }}{{ lab.unit ? " " + lab.unit : "" }}</strong>',
    ),
    [],
  );
});

test('QA D1135 / D1106 guard: a percentage takes its sign from the formatter', () => {
  // round 20: the bed board's "16.7%" in French and Spanish
  const raw = [
    '<span class="census-value">{{ b.census.occupancyRate }}%</span>',
    "<span>{{ rate | number: '1.1-1' }}%</span>",
    "{{ (match.score * 100 | number: '1.0-1') + '%' }}",
    "{{ 'BED_BOARD.WARD_STATS' | translate: { occupied: o, total: t, rate: ward.occupancyRate } }}",
  ];
  for (const html of raw) assert.ok(decimalTemplateFindings(html).length >= 1, html);
  const clean = [
    "<span>{{ b.census.occupancyRate | measure: '%' }}</span>",
    "{{ 'X' | translate: { rate: (ward.occupancyRate | number: '1.0-1') } }}",
  ];
  for (const html of clean) assert.deepEqual(decimalTemplateFindings(html), [], html);
  assert.equal(decimalCodeFindings("const label = pct + '%';").length, 1);
  assert.equal(decimalCodeFindings('const s = `${pct}%`;').length, 1);
  assert.deepEqual(decimalCodeFindings('barHeight(n: number) { return `${n * 100}%`; }'), []);
});

test('QA D1166 (round 21) guard: the sign typed after markup is a typed sign too', () => {
  // The doctor dashboard ring ("0% Terminé") and the chart vitals ("97%").
  const raw = [
    '<span class="ring-pct">{{ scheduleProgress() }}<small>%</small></span>',
    '<strong>{{ v.spo2Percent }}</strong\n  ><small>%</small>',
    '<span class="score-number">{{ score() }}</span>\n<span class="score-percent">%</span>',
    '<b>{{ x }}</b> %',
  ];
  for (const html of raw) assert.equal(decimalTemplateFindings(html)[0]?.rule, 'percent', html);
  const clean = [
    '<span class="ring-pct">{{ scheduleProgress() | measure: \'%\' : 0 }}</span>',
    // Text or another interpolation between them: the "%" belongs to something else.
    '<span>{{ a }}</span> sur <span>100 %</span>',
    '<span>{{ a }}</span><span>{{ b }}</span>',
  ];
  for (const html of clean) assert.deepEqual(decimalTemplateFindings(html), [], html);
});
