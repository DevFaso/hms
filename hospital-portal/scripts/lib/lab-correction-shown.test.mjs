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
import { test } from 'node:test';
import assert from 'node:assert/strict';
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
const MARKS_CORRECTION = /<app-lab-correction-badge\b|supersedesResultId/;

/** Whether a template shows a lab result value, and whether it marks a correction. */
export function correctionShape(html) {
  return {
    showsValue: SHOWS_VALUE.some((re) => re.test(html)),
    marksCorrection: MARKS_CORRECTION.test(html),
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
  const unmarked = showing
    .filter(([, html]) => !correctionShape(html).marksCorrection)
    .map(([f]) => f);
  assert.deepEqual(unmarked, []);
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
