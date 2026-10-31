// QA D1049 guard (round 17): no ISO date reaches a template.
//
// French clinical screens showed "cake 1991-07-22" (the patient banner's date
// of birth) and "Rendez-vous de suivi 2026-10-31" (the visit summary): the
// stored ISO string was interpolated as it came from the API. The lab screens
// already had this rule (lab-locale-format.test.mjs, QA D1002); this is the
// same rule for every template of the portal: a date-shaped field
// (…Date, …At, …Datetime, …Timestamp, dateOfBirth, dob) interpolated in a
// template must go through `| date` (after `| serverTime` for a server
// clock value) or a formatting helper (`formatDate(`, `format…(`, `fmt…(`).
// Sites that violate it today are pinned below with their defect id; the
// list only shrinks.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

// QA D1119 (round 19): a range bound (`requestedDateFrom`, `requestedDateTo`,
// `validDateUntil`) and a bare `.date` field are dates too; the waitlist showed
// "2026-11-02 → 2026-11-13" because `requestedDateFrom` ended in "From".
const DATE_FIELD =
  /(?:\.|\b)[a-zA-Z]*(?:Date|At|Datetime|DateTime|Timestamp)(?:From|To|Start|End|Until)?\b|\bdateOfBirth\b|\bdob\b|\.date\b/;
const FORMATTED = /\|\s*(?:date|dayMonth)\b|\bformat\w*\(|\bfmt\w*\(|\|\s*(?:relativeTime|timeAgo|age)\b/;

/** The ISO dates one template interpolates unformatted. */
export function isoDateOffenders(html, file = 'template') {
  const offenders = [];
  for (const m of html.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
    const expr = m[1];
    if (/^\s*'[^']*'\s*\|\s*translate/.test(expr)) continue;
    if (FORMATTED.test(expr)) continue;
    // A date token that is printed: not a method (`charAt(`), not an argument
    // handed to a helper (`getAge(p.dateOfBirth)`), not a ternary's condition.
    const printed = [...expr.matchAll(new RegExp(DATE_FIELD.source, 'g'))].some((t) => {
      const after = expr.slice(t.index + t[0].length);
      const before = expr.slice(0, t.index);
      if (/^\s*\(/.test(after)) return false;
      if (/^\s*\?(?![?.])/.test(after)) return false;
      if (/\b\w+\(\s*[\w.!?]*$/.test(before)) return false;
      return true;
    });
    if (printed) {
      offenders.push(`${file}::${expr.trim().replace(/\s+/g, ' ')}`);
    }
  }
  return offenders;
}

/**
 * Known sites, each with its defect id. Shrink only: a fixed site fails the
 * test until its line is deleted here.
 */
const PINNED = {};

test('QA D1049: every date a template shows is formatted for the reader', () => {
  // QA D1119: the inline `template:` of a component too, not only `.html`.
  const found = templatesIn(appDir).flatMap(({ file, text }) =>
    isoDateOffenders(text, relative(appDir, file).replaceAll('\\', '/')),
  );
  const counts = {};
  for (const o of found) counts[o] = (counts[o] ?? 0) + 1;
  const unpinned = Object.keys(counts).filter((k) => !(k in PINNED));
  const stale = Object.keys(PINNED).filter((k) => !(k in counts));
  assert.deepEqual(unpinned, [], 'ISO date interpolated without | date (QA D1049)');
  assert.deepEqual(stale, [], 'the site was fixed: delete its pin');
});

test('QA D1049 guard: the scan sees the shapes the defect had', () => {
  assert.equal(isoDateOffenders('<span>{{ p.dateOfBirth }}</span>').length, 1);
  assert.equal(isoDateOffenders('<p>{{ summary.followUpAppointmentDate }}</p>').length, 1);
  assert.equal(isoDateOffenders('<td>{{ r.completedAt ?? r.requestedAt }}</td>').length, 1);
  // QA D1119: the waitlist's requested range, and a bare `.date` field.
  assert.equal(
    isoDateOffenders(
      "<td>{{ entry.requestedDateFrom ?? '?' }} → {{ entry.requestedDateTo ?? '?' }}</td>",
    ).length,
    2,
  );
  assert.equal(isoDateOffenders('<td>{{ appt.date }}</td>').length, 1);
  assert.deepEqual(
    isoDateOffenders("<td>{{ entry.requestedDateFrom | date: 'mediumDate' }}</td>"),
    [],
  );
  assert.deepEqual(
    isoDateOffenders(
      "{{ p.dateOfBirth | date: 'mediumDate' }} {{ a.createdAt | serverTime | date: 'short' }} {{ formatDate(x.dueDate) }}",
    ),
    [],
  );
});

// QA D1140 (round 20): a date pattern that fixes the field ORDER. The portal
// dashboard's lab card wrote `date: 'MMM d'` — the English order in every
// language ("oct. 31" in French); pharmacy screens wrote `'dd/MM/yyyy'`, the
// French order for an English reader. A pattern naming both a day and a month
// is forbidden: use a named format ('shortDate', 'mediumDate', 'short') or
// `| dayMonth` (shared/pipes/day-month.pipe.ts) for a day and month alone.
const FIXED_ORDER_DATE = /\|\s*date\s*:\s*'([^']*)'/g;

/** The fixed-order date patterns one template uses. */
export function fixedOrderDates(html, file = 'template') {
  const out = [];
  for (const m of html.matchAll(FIXED_ORDER_DATE)) {
    const pattern = m[1];
    if (/(?<![A-Za-z])d{1,2}(?![A-Za-z])/.test(pattern) && /M/.test(pattern)) {
      out.push(`${file}::${pattern}`);
    }
  }
  return out;
}

/** Owned by another fix branch this round; listed so the hand-back names it. */
const FIXED_ORDER_PINNED = {
  'reception/waitlist-panel/waitlist-panel.component.html::EEE d MMM, HH:mm':
    'D1140 sibling in fix/r20-portal-forms files (waitlist-panel): not touched here, see hand-back',
};

test('QA D1140: no template fixes the order of a day and a month', () => {
  const found = templatesIn(appDir).flatMap(({ file, text }) =>
    fixedOrderDates(text, relative(appDir, file).replaceAll('\\', '/')),
  );
  const unpinned = found.filter((k) => !(k in FIXED_ORDER_PINNED));
  const stale = Object.keys(FIXED_ORDER_PINNED).filter((k) => !found.includes(k));
  assert.deepEqual(unpinned, [], 'a fixed-order date pattern (QA D1140)');
  assert.deepEqual(stale, [], 'the site was fixed: delete its pin');
});

test('QA D1140 guard: the scan sees the shapes the defect had, and passes the named formats', () => {
  assert.equal(fixedOrderDates("{{ lab.collectedDate | serverTime | date: 'MMM d' }}").length, 1);
  assert.equal(fixedOrderDates("{{ c.createdAt | date: 'dd/MM/yyyy HH:mm' }}").length, 1);
  assert.deepEqual(
    fixedOrderDates(
      "{{ a | date: 'mediumDate' }} {{ b | date: 'MMM yyyy' }} {{ c | date: 'EEE' }} {{ d | dayMonth }}",
    ),
    [],
  );
});
