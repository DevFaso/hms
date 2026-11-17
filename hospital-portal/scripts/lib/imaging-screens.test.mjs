// r44 imaging review guard (D1574 / D1575 classes on the Imagerie screens).
//
// The French report drawer read "Report signed" and "Report authored" (the
// server's English status reason), "13/11/2026 08:07" where the app writes
// "13 nov. 2026, 08:07", the order drawer printed the raw laterality code, and
// the signature digest sat in the clinician's line. This reads every imaging
// template and fails when one of those shapes comes back:
//   - a date shown with a numeric format (date: 'short' / 'shortDate' / a
//     dd/MM pattern): the app's own pipes are | dateTime and date: 'mediumDate';
//   - a status reason shown without first checking the event code;
//   - a laterality, status or priority interpolated without | enumLabel;
//   - the signature digest shown outside its details toggle.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const NUMERIC_DATE = /\|\s*date\s*:\s*'(?:short|shortDate|[^']*(?:dd\/|\/MM|MM\/))[^']*'/;
const RAW_ENUM_FIELD = /\.(laterality|status|priority|reportStatus)\b/;

/** The offenders in one imaging template's text. */
export function imagingOffenders(html, file = 'template') {
  const offenders = [];
  const at = (index) => `${file}:${html.slice(0, index).split('\n').length}`;
  for (const m of html.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
    const expr = m[1];
    if (NUMERIC_DATE.test(expr)) {
      offenders.push(`${at(m.index)} numeric date format: ${expr.trim()}`);
    }
    const field = RAW_ENUM_FIELD.exec(expr);
    // Inside a tag (class="… {{ getStatusClass(o.status) }}") it styles, it is not read.
    const head = html.slice(0, m.index);
    const inTag = head.lastIndexOf('<') > head.lastIndexOf('>');
    if (
      field &&
      !inTag &&
      !/\|\s*(enumLabel|translate)\b/.test(expr) &&
      !/'IMAGING\.REPORT_STATUS_'\s*\+/.test(expr)
    ) {
      offenders.push(`${at(m.index)} raw ${field[1]} without | enumLabel: ${expr.trim()}`);
    }
    if (/\.statusReason\b/.test(expr)) {
      const before = html.slice(Math.max(0, m.index - 400), m.index);
      if (!/eventCode/.test(before)) {
        offenders.push(`${at(m.index)} status reason shown without its event code`);
      }
    }
    if (/\.signatureValue\b/.test(expr)) {
      const before = html.slice(0, m.index);
      const open = before.lastIndexOf('<details');
      if (open < 0 || before.lastIndexOf('</details>') > open) {
        offenders.push(`${at(m.index)} signature digest outside its details toggle`);
      }
    }
  }
  return offenders;
}

test('r44: the imaging screens show app-formatted dates, translated codes and no bare digest', () => {
  const files = walk(join(appDir, 'imaging'), ['.html']);
  assert.ok(files.length >= 1);
  const offenders = files.flatMap((f) =>
    imagingOffenders(readFileSync(f, 'utf8'), relative(appDir, f).replaceAll('\\', '/')),
  );
  assert.deepEqual(offenders, []);
});

test('r44 guard: the scan sees the shapes the review found', () => {
  assert.equal(imagingOffenders("{{ h.changedAt | serverTime | date: 'short' }}").length, 1);
  assert.equal(imagingOffenders("{{ x | date: 'dd/MM/yyyy HH:mm' }}").length, 1);
  assert.equal(imagingOffenders("{{ ord.laterality || '—' }}").length, 1);
  assert.deepEqual(imagingOffenders('<span class="b {{ getStatusClass(o.status) }}">x</span>'), []);
  assert.equal(imagingOffenders("{{ h.statusReason ? '· ' + h.statusReason : '' }}").length, 1);
  assert.equal(imagingOffenders('<code>{{ rep.signatureValue.slice(0, 16) }}</code>').length, 1);
  assert.deepEqual(
    imagingOffenders(
      "{{ h.changedAt | serverTime | dateTime }} {{ o.orderedAt | serverTime | date: 'mediumDate' }}" +
        " {{ ord.laterality | enumLabel: 'imagingLaterality' }}" +
        " {{ 'IMAGING.REPORT_STATUS_' + h.status | translate }}" +
        ' @if (h.eventCode) { x } @else if (h.statusReason) { {{ h.statusReason }} }' +
        ' <details><summary>i</summary><code>{{ rep.signatureValue }}</code></details>',
    ),
    [],
  );
});
