// QA D1016 guard (the D931 class): every place a lab result is shown handles a
// superseding result.
//
// D931 (round 16) marked a released correction on the laboratory's own
// /lab-results only. Round 17 found the same corrected "Négatif" shown as a
// first, unrelated result on the patient portal, the in-basket table, the
// chart review and the release worklist. This reads every template that
// shows a lab result's value and fails unless it also shows the correction
// marker (<app-lab-correction-badge>, or the laboratory's own
// supersedesResultId badge on /lab-results).
//
// A template "shows a lab result's value" when it interpolates `.resultValue`,
// pipes something other than a stock quantity through `| labValue`, or prints
// the patient-side value of a lab row (`lab.result`, `l.result`, `lab.value`).
//
// Round 18 (D1016/D931 NOT_FIXED a second time): the chart review's
// Chronologie rendered every event, results included, through a generic
// `{{ event.title }}` — the value rode inside the title, so none of the
// shapes above saw it and the timeline had no badge. An aggregate list (an
// `@for` over a `….timeline` / timeline `entries` array whose rows are printed
// through a generic `.title` / `.summary`) now counts as showing a lab value.
// The patient chart's Chronologie is the one aggregate that marks the
// correction in the row text the server writes (PatientServiceImpl's
// lab.result.correctedLabel suffix, held by LabResultReleaseAnnouncementGuardTest);
// it is listed below with that reason, not skipped silently.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const SHOWS_VALUE = [
  /\{\{[^}]*\.resultValue\b[^}]*\}\}/,
  /\{\{(?![^}]*\.quantity\b)[^}]*\|\s*labValue\b[^}]*\}\}/,
  /\{\{[^}]*\b(?:lab|l)\.(?:result|value)\b(?!\w)[^}]*\}\}/,
  /displayResult\(\s*lab\.result\s*\)/,
];

/** An `@for` over a timeline-shaped array; group 1 is the row variable, group 2 the iterated expression. */
const AGGREGATE_LOOP =
  /@for\s*\(\s*(\w+)\s+of\s+([\w.()?!]*?(?:timeline|[tT]imeline\w*|tl\.entries)\b[^;]*);[^)]*\)\s*\{/g;
/**
 * An `@for` over a lab-result list (the iterated expression names labs or
 * results). Round 18: the patient chart's Labs table printed `{{ r.value }}`
 * — a row variable none of the SHOWS_VALUE shapes (`lab.`, `l.`) knew.
 */
const LAB_LIST_LOOP =
  /@for\s*\(\s*(\w+)\s+of\s+([^;]*(?:\w*[rR]esults?|\blabs)\b[^;]*);[^)]*\)\s*\{/g;

/**
 * Any `@for` (round 18 sibling: the lab-results trend history iterated
 * `cmp.trendHistory` and printed `pt.resultValue`, a list name the shapes
 * above did not know). A row printing `resultValue` is a lab value.
 */
const ANY_LOOP = /@for\s*\(\s*(\w+)\s+of\s+([^;]*);[^)]*\)\s*\{/g;

/** The `{ … }` block that opens at `open` (balanced braces; `{{ }}` interpolations balance too). */
function blockAt(html, open) {
  let depth = 0;
  for (let i = open; i < html.length; i++) {
    if (html[i] === '{') depth++;
    else if (html[i] === '}' && --depth === 0) return html.slice(open, i + 1);
  }
  return html.slice(open);
}

/** Loop bodies matched by `loop` whose row variable prints one of `fields`. */
function loopBodies(html, loop, fields) {
  const bodies = [];
  for (const m of html.matchAll(loop)) {
    const v = m[1];
    const body = blockAt(html, m.index + m[0].length - 1);
    if (new RegExp(String.raw`\{\{[^}]*\b${v}\.(?:${fields})\b(?![\w(])[^}]*\}\}`).test(body)) {
      bodies.push({ over: m[2].trim(), body });
    }
  }
  return bodies;
}

/**
 * The bodies of the loops that show lab values row by row: aggregate
 * timelines printing a generic title/summary, and lab-result lists printing a
 * row's value. Checked per loop body, not per file: the round-17 chart review
 * carried a badge on its Results tab, so a file-level check passed while its
 * Chronologie had none (why D1016 came back).
 */
function rowBodies(html) {
  return [
    ...loopBodies(html, AGGREGATE_LOOP, 'title|summary'),
    ...loopBodies(html, LAB_LIST_LOOP, 'value|result'),
    ...loopBodies(html, ANY_LOOP, 'resultValue'),
  ];
}

/**
 * Aggregates that mark a correction in the row text the server writes, by
 * the expression they iterate, and why. Only that loop is excused, never the
 * rest of its file.
 */
const SERVER_MARKED = new Map([
  [
    'tl.entries',
    'the patient chart Chronologie: LAB_RESULT rows carry the "(Corrigé)" suffix in their summary (PatientServiceImpl, lab.result.correctedLabel)',
  ],
]);
const MARKS_CORRECTION = /<app-lab-correction-badge\b|supersedesResultId/;

/** Whether a template shows a lab result value, and whether it marks a correction. */
export function correctionShape(html) {
  const direct = SHOWS_VALUE.some((re) => re.test(html));
  const rows = rowBodies(html);
  return {
    showsValue: direct || rows.length > 0,
    marksCorrection:
      (!direct || MARKS_CORRECTION.test(html)) &&
      rows.every(({ over, body }) => SERVER_MARKED.has(over) || MARKS_CORRECTION.test(body)),
  };
}

test('QA D1016: every template that shows a lab result value marks a correction', () => {
  const byFile = new Map();
  for (const { file, text } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    byFile.set(rel, (byFile.get(rel) ?? '') + '\n' + text);
  }
  const showing = [...byFile].filter(([, html]) => correctionShape(html).showsValue);
  const names = showing.map(([f]) => f);
  for (const known of [
    'patient-portal/my-lab-results/my-lab-results.component.html',
    'in-basket/lab-results-inbox/lab-results-inbox.html',
    'lab/lab-release-worklist/lab-release-worklist.html',
    'patients/chart-review/chart-review.component.html',
    'lab/lab-results/lab-results.html',
  ]) {
    assert.ok(names.includes(known), `the scan must see ${known}`);
  }
  assert.ok(
    names.includes('patients/patient-chart/patient-chart.component.html'),
    'the scan must see the patient chart Chronologie (an aggregate timeline)',
  );
  const unmarked = showing
    .filter(([, html]) => !correctionShape(html).marksCorrection)
    .map(([f]) => f);
  assert.deepEqual(unmarked, []);
});

test('QA D1016 round 18: an aggregate timeline printing a generic title shows a lab value', () => {
  // The chart-review Chronologie as it shipped in round 18: no badge.
  const old = `@for (event of c.timeline; track event.id) {
    <p class="chart-review__timeline-title">{{ event.title }}</p> }`;
  assert.deepEqual(correctionShape(old), { showsValue: true, marksCorrection: false });
  // A badge on another tab of the same file does not mark the timeline (why D1016 came back).
  assert.equal(
    correctionShape(
      old + '<td>{{ r.resultValue }} <app-lab-correction-badge [corrected]="r.corrected" /></td>',
    ).marksCorrection,
    false,
  );
  assert.equal(
    correctionShape(
      `@for (event of c.timeline; track event.id) { <p>{{ event.title }}
        <app-lab-correction-badge [corrected]="event.corrected" /></p> }`,
    ).marksCorrection,
    true,
  );
  assert.equal(
    correctionShape(`@for (e of tl.entries; track e.entryId) { <div>{{ e.summary }}</div> }`)
      .showsValue,
    true,
  );
  // A loop over something else, or a timeline printing only typed fields, is not one.
  assert.equal(
    correctionShape(`@for (e of t.items; track e.id) { {{ e.title }} }`).showsValue,
    false,
  );
  assert.equal(
    correctionShape(`@for (e of t.timeline; track e.id) { {{ e.medicationName }} }`).showsValue,
    false,
  );
  // The server-marked loop excuses itself only, never a lab table beside it.
  assert.equal(
    correctionShape(
      `@for (e of tl.entries; track e.entryId) { {{ e.summary }} }
       @for (r of labResults(); track r.id) { <td>{{ r.value }}</td> }`,
    ).marksCorrection,
    false,
  );
  // Every listed server-marked aggregate still exists (the list cannot outlive its site).
  const all = [...templatesIn(appDir)].map(({ text }) => text).join('\n');
  for (const over of SERVER_MARKED.keys()) {
    assert.ok(all.includes(`of ${over};`), `stale SERVER_MARKED entry: ${over}`);
  }
});

test('QA D1016 round 18: a lab-result list printing a row value is seen whatever the row is called', () => {
  // The patient chart's Labs table as it shipped in round 18: no badge.
  assert.deepEqual(
    correctionShape(`@for (r of labResults(); track r.id) { <span>{{ r.value || '—' }}</span> }`),
    { showsValue: true, marksCorrection: false },
  );
  assert.equal(
    correctionShape(`@for (r of labResults(); track r.id) { {{ r.value }}
      <app-lab-correction-badge [corrected]="r.corrected" /> }`).marksCorrection,
    true,
  );
  // A lab list printing only its names and dates shows no value.
  assert.equal(
    correctionShape(`@for (l of labs(); track l.id) { {{ l.testName }} {{ l.valueSetName() }} }`)
      .showsValue,
    false,
  );
});

test('QA D1016 guard: the scan sees the shapes the defect had', () => {
  assert.deepEqual(correctionShape('<td>{{ r.resultValue | labValue }}</td>'), {
    showsValue: true,
    marksCorrection: false,
  });
  assert.equal(correctionShape('<b>{{ displayResult(lab.result) }}</b>').showsValue, true);
  assert.equal(
    correctionShape('<td>{{ item.quantity | labValue: item.unit }}</td>').showsValue,
    false,
  );
  assert.equal(
    correctionShape('{{ r.resultValue }} <app-lab-correction-badge [corrected]="r.corrected" />')
      .marksCorrection,
    true,
  );
});

test('QA D1016 round 18: a trend history row printing resultValue is a lab value, whatever the list is called', () => {
  const badgeElsewhere = `<p>{{ r.resultValue }} <app-lab-correction-badge [corrected]="r.corrected" /></p>`;
  const trend = `@for (pt of cmp.trendHistory; track pt.labResultId) { <td>{{ pt.resultValue | labValue }}</td> }`;
  assert.deepEqual(correctionShape(badgeElsewhere + trend), {
    showsValue: true,
    marksCorrection: false,
  });
  const fixed = trend.replace(
    '</td>',
    '<app-lab-correction-badge [corrected]="pt.corrected" /></td>',
  );
  assert.equal(correctionShape(badgeElsewhere + fixed).marksCorrection, true);
});

// QA D1137 (round 20): the chart review's Résultats placed the "Corrigé"
// badge between the value and its unit ("6,8 [Corrigé] g/dL", read aloud
// "6,8 Corrigég/dL"). The badge comes after the unit: no unit may follow a
// badge inside the same cell or line.
export function badgeBeforeUnit(html) {
  const out = [];
  for (const m of html.matchAll(/<app-lab-correction-badge[\s\S]*?\/>/g)) {
    const after = html.slice(m.index + m[0].length, m.index + m[0].length + 300);
    const cell = after.split(/<\/(?:td|p|div|li|dd)>/)[0];
    if (/[Uu]nit\b/.test(cell)) out.push(cell.replace(/\s+/g, ' ').trim().slice(0, 80));
  }
  return out;
}

test('QA D1137: the correction badge never sits between a value and its unit', () => {
  const offenders = templatesIn(appDir).flatMap(({ file, text }) =>
    badgeBeforeUnit(text).map((c) => `${relative(appDir, file).replaceAll('\\', '/')}: ${c}`),
  );
  assert.deepEqual(offenders, []);
  assert.equal(
    badgeBeforeUnit(
      '<td>{{ r.resultValue | labValue }} <app-lab-correction-badge [corrected]="r.corrected" /> @if (r.resultUnit) { <span>{{ r.resultUnit }}</span> }</td>',
    ).length,
    1,
  );
});

// QA D1167 / D1137 (round 21, NOT_FIXED a second time): the in-basket read
// "12,8 g/dLCorrigéBas" and the portal dashboard "12,8 g/dLCorrigé (13,5 –
// 17,5 g/dL)". Angular drops the whitespace-only text between `</span>` and
// the next element, so a marker written on its own line is glued to its
// neighbour in the text a screen reader speaks and a copy pastes (the CSS
// margin only separates them on screen). The round-20 check saw only a unit
// AFTER the badge.
//
// 1. The shared badge carries a word space on each side itself, so every one
//    of its usages (enumerated below) is spaced by construction.
// 2. A correction marker written by hand (the laboratory's own
//    `status-correction` badge on /lab-results) and the status badges beside
//    it are preceded by `&ngsp;` or `{{ ' ' }}` when an element comes before
//    them in the same cell.
const BADGE_SOURCE = join(appDir, 'lab', 'shared', 'lab-correction-badge.component.ts');

export function badgeCarriesSpaces(source) {
  const block = /@if\s*\(\s*corrected\(\)\s*\)\s*\{([\s\S]*?)\}\s*`/.exec(source)?.[1] ?? '';
  return /^\s*&ngsp;\s*<span\b/.test(block) && /<\/span\s*>\s*&ngsp;\s*$/.test(block);
}

/**
 * Is the element opening at `index` separated from what comes before it in the
 * same cell? Whitespace, comments and the `@if (…) {` / `@else {` heads that
 * wrap it are stepped over. A `&ngsp;` / `{{ ' ' }}` before it, or the opening
 * tag of its cell (it is the first thing in it), is a separation; a closing tag
 * or the `}` that ends the previous block is a neighbour it is glued to.
 */
export function spacedBefore(html, index) {
  let text = html.slice(0, index);
  for (;;) {
    text = text.trimEnd();
    if (text.endsWith('-->')) {
      text = text.slice(0, text.lastIndexOf('<!--'));
      continue;
    }
    const head = /(?:@if\s*\([^{}]*\)|@else(?:\s+if\s*\([^{}]*\))?)\s*\{$/.exec(text);
    if (!head) break;
    const isElse = head[0].startsWith('@else');
    text = text.slice(0, head.index);
    // `} @else {`: the branch before is never rendered with this one: step
    // over it (and its own head) too.
    if (isElse) text = withoutBlockBefore(text);
  }
  if (text.endsWith('&ngsp;') || /\{\{\s*' '\s*\}\}$/.test(text)) return true;
  if (text.endsWith('>')) {
    const open = text.lastIndexOf('<');
    const tag = text.slice(open);
    return !tag.startsWith('</') && !tag.endsWith('/>');
  }
  return false;
}

/** The `{ … }` block that ends `text` and the `@if (…)` / `@else` head that opens it, removed. */
function withoutBlockBefore(text) {
  const trimmed = text.trimEnd();
  if (!trimmed.endsWith('}')) return text;
  let depth = 0;
  for (let i = trimmed.length - 1; i >= 0; i--) {
    if (trimmed[i] === '}') depth++;
    else if (trimmed[i] === '{' && --depth === 0) {
      const before = trimmed.slice(0, i);
      const head = /(?:@if\s*\([^{}]*\)|@else(?:\s+if\s*\([^{}]*\))?)\s*$/.exec(before);
      if (!head) return text;
      const rest = before.slice(0, head.index);
      return head[0].startsWith('@else') ? withoutBlockBefore(rest) : rest;
    }
  }
  return text;
}

/** A correction marker written by hand: the laboratory's own `status-correction` badge. */
const HAND_MARKER = /<span\b[^>]*\bclass="[^"]*\bstatus-correction\b[^"]*"/g;

test('QA D1167: the shared correction badge carries a word space on each side', () => {
  assert.equal(badgeCarriesSpaces(readFileSync(BADGE_SOURCE, 'utf8')), true);
  // Every usage, enumerated: each is the shared component, spaced by construction.
  const usages = templatesIn(appDir).flatMap(({ file, text }) =>
    [...text.matchAll(/<app-lab-correction-badge\b/g)].map(() =>
      relative(appDir, file).replaceAll('\\', '/'),
    ),
  );
  assert.ok(usages.length >= 15, `found ${usages.length} usages: ${usages.join(', ')}`);
  const unspaced = '@if (corrected()) {\n      <span class="x">C</span>\n    }\n  `';
  assert.equal(badgeCarriesSpaces(unspaced), false);
});

test('QA D1167: a hand-written correction marker is never glued to the badge before it', () => {
  const offenders = templatesIn(appDir).flatMap(({ file, text }) =>
    [...text.matchAll(HAND_MARKER)]
      .filter((m) => !spacedBefore(text, m.index))
      .map(
        (m) =>
          `${relative(appDir, file).replaceAll('\\', '/')}:${text.slice(0, m.index).split('\n').length} ${m[0].slice(0, 70)}`,
      ),
  );
  assert.deepEqual(offenders, []);
});

test('QA D1167 guard: the spacing scan sees the round-21 shapes', () => {
  const glued = `<td><span class="status-badge a">Publié</span>
    @if (r.supersedesResultId) {
      <!-- a correction -->
      <span class="status-badge status-correction">Corrigé</span>
    }</td>`;
  const at = glued.indexOf('<span class="status-badge status-correction"');
  assert.equal(spacedBefore(glued, at), false);
  const fixed = glued.replace(
    '<span class="status-badge status-correction"',
    '&ngsp;<span class="status-badge status-correction"',
  );
  assert.equal(
    spacedBefore(fixed, fixed.indexOf('<span class="status-badge status-correction"')),
    true,
  );
  // The first thing in its cell needs no space.
  const first = '<td>\n  <span class="status-badge x">A</span></td>';
  assert.equal(spacedBefore(first, first.indexOf('<span')), true);
  assert.equal(spacedBefore("{{ ' ' }}<span>", "{{ ' ' }}".length), true);
});
