// QA D1215 guard (round 23): a date range that ends before it starts, and a
// front-desk date that schedules forward set in the past.
//
// The reception waitlist saved a preferred window "from 20/11/2026 to
// 10/11/2026" (it can never be offered a slot), and a recall due 01/01/2020
// (overdue the moment it was made). Neither picker had a bound, and neither
// submit checked.
//
// Rule 1 (the whole portal): every date / datetime-local input that reads as
// the END of a range (its id, name, formControlName or model names `to`,
// `end`, `ends` or `until`, and not `start` / `from`) binds a lower bound
// (`[min]`, `[attr.min]` or `min=`), so the picker never offers an end
// before the start. A write form also refuses a typed reversed range on the
// field (shared/forms/date-rules.ts `endsBeforeStart` + `refuseFields`).
//
// Rule 2 (the front desk): every date input under reception/ is classified,
// either as a forward-scheduling date that binds a lower bound (a recall
// due date: never in the past) or as a pinned site with why it may be past.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const DATE_INPUT = /<input\b[^>]*?\btype="(?:date|datetime-local)"[^>]*>/g;

/** The words an input is named by: id, name, formControlName and its model's last member. */
export function nameWords(tag) {
  const names = [];
  for (const m of tag.matchAll(/\b(?:id|name|formControlName)="([^"]+)"/g)) names.push(m[1]);
  const model = /(?:\[\(ngModel\)\]|\[ngModel\]|\[value\])="([^"]+)"/.exec(tag);
  if (model) {
    const member = /([A-Za-z_$][\w$]*)\s*(?:\(\))?\s*(?:\?\?[^"]*)?$/.exec(model[1].trim());
    if (member) names.push(member[1]);
  }
  return names.flatMap((n) =>
    n
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
      .split(/[\s\-_.]+/)
      .map((w) => w.toLowerCase())
      .filter(Boolean),
  );
}

/** The input reads as the end of a range. */
export function isRangeEnd(tag) {
  const words = nameWords(tag);
  return (
    words.some((w) => ['to', 'end', 'ends', 'until'].includes(w)) &&
    !words.some((w) => ['start', 'starts', 'from'].includes(w))
  );
}

/** The input binds a lower bound. */
export function hasLowerBound(tag) {
  return /\[(?:attr\.)?min\]="|\smin="/.test(tag);
}

/** Every date input of a source: `{ tag, line }`. */
export function dateInputs(source) {
  return [...source.matchAll(DATE_INPUT)].map((m) => ({
    tag: m[0],
    line: source.slice(0, m.index).split('\n').length,
  }));
}

/** Front-desk date inputs that may hold a past date, each with why (rule 2). */
const PAST_ALLOWED = new Map([
  [
    'reception/reception-cockpit/reception-cockpit.component.html::reception-cockpit-date',
    "the day the cockpit shows: yesterday's desk is read back, nothing is scheduled",
  ],
  [
    'reception/waitlist-panel/waitlist-panel.component.html::wl-from',
    'the start of a preferred window: a window opened earlier still offers its later days; its end is bounded by rule 1 and checked on submit',
  ],
  [
    'reception/empi-candidates-panel/empi-candidates-panel.component.ts::empi-dob',
    'a date of birth searched for: always in the past',
  ],
]);

function idOf(tag) {
  return /\bid="([^"]+)"/.exec(tag)?.[1] ?? '?';
}

test('QA D1215: every range-end date input in the portal binds a lower bound', () => {
  const problems = [];
  let inputs = 0;
  for (const file of walk(appDir, ['.html', '.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const { tag, line } of dateInputs(readFileSync(file, 'utf8'))) {
      inputs++;
      if (isRangeEnd(tag) && !hasLowerBound(tag)) {
        problems.push(
          `${rel}:${line} ${idOf(tag)}: the end of a range with no [attr.min] bound to its start`,
        );
      }
    }
  }
  assert.ok(inputs > 100, `every date input must be read, saw ${inputs}`);
  assert.deepEqual(problems, [], 'bind the end to its start: [attr.min]="start || null"');
});

test('QA D1215: every front-desk date input is bounded below or pinned with why it may be past', () => {
  const problems = [];
  const seen = new Set();
  for (const file of walk(join(appDir, 'reception'), ['.html', '.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const { tag, line } of dateInputs(readFileSync(file, 'utf8'))) {
      const key = `${rel}::${idOf(tag)}`;
      seen.add(key);
      if (!hasLowerBound(tag) && !PAST_ALLOWED.has(key)) {
        problems.push(
          `${rel}:${line} ${idOf(tag)}: bind [attr.min] (a front-desk date that schedules forward) or pin it with why`,
        );
      }
    }
  }
  assert.deepEqual(problems, []);
  const stale = [...PAST_ALLOWED.keys()].filter((k) => !seen.has(k));
  assert.deepEqual(stale, [], 'a pinned site is gone: delete its pin');
});

test('QA D1215 guard: the scan sees the round-23 shapes and passes the fix', () => {
  const before =
    '<input id="wl-to" class="form-input" type="date" [ngModel]="dateTo()" (ngModelChange)="dateTo.set($event)" />';
  assert.ok(isRangeEnd(before));
  assert.equal(hasLowerBound(before), false);
  const after =
    '<input id="wl-to" class="form-input" type="date" [attr.min]="dateFrom() || null" [ngModel]="dateTo()" />';
  assert.ok(hasLowerBound(after));
  // The start of a range, a single date and an EDD are not range ends.
  assert.equal(isRangeEnd('<input id="wl-from" type="date" [ngModel]="dateFrom()" />'), false);
  assert.equal(isRangeEnd('<input id="rc-due" type="date" [ngModel]="dueDate()" />'), false);
  assert.equal(
    isRangeEnd('<input id="bp-edd" type="date" [(ngModel)]="plan.expectedDueDate" />'),
    false,
  );
  // Named only by its model, camelCase or a member chain.
  assert.ok(isRangeEnd('<input type="date" [(ngModel)]="form.endDate" />'));
  assert.ok(isRangeEnd('<input type="datetime-local" [ngModel]="releaseForm().endsAt" />'));
  assert.ok(isRangeEnd('<input type="date" name="to" [(ngModel)]="to" />'));
  assert.equal(isRangeEnd('<input type="date" [(ngModel)]="form.startDate" />'), false);
  assert.equal(dateInputs(`<div>\n<input type="date" id="a" />\n</div>`)[0].line, 2);
});
