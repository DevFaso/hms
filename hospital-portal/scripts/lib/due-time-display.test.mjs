// QA D949 guard: a due time is never shown as a bare clock time.
//
// The MAR showed tomorrow's 23:04 dose as "19:04 À administrer": a time-only
// column reads as today. Every template that shows a due time (…dueTime,
// …dueAt) with a time-only format must fall back to a date when the slot is
// not today (isDueToday / isServerTimeToday).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/** Interpolations of a due field formatted as a time only, with no other-day fallback. */
export function bareDueTimes(html, file = 'template') {
  const offenders = [];
  for (const m of html.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
    const expr = m[1].replace(/\s+/g, ' ');
    if (/\.(dueTime|dueAt)\b/.test(expr) && /date: ?'(shortTime|HH:mm)'/.test(expr)) {
      offenders.push(`${file}:${html.slice(0, m.index).split('\n').length} ${expr.trim()}`);
    }
  }
  return offenders;
}

test('QA D949: no due time is shown as a bare clock time', () => {
  const offenders = walk(appDir, ['.html']).flatMap((f) =>
    bareDueTimes(readFileSync(f, 'utf8'), relative(appDir, f).split('\\').join('/')),
  );
  assert.deepEqual(offenders, []);
});

test('QA D949 guard: the scan sees the shape the defect had', () => {
  assert.equal(bareDueTimes("<td>{{ m.dueTime | serverTime | date: 'shortTime' }}</td>").length, 1);
  assert.equal(
    bareDueTimes(
      "<td>{{ m.dueTime | serverTime | date: (isDueToday(m.dueTime) ? 'shortTime' : 'short') }}</td>",
    ).length,
    0,
  );
});
