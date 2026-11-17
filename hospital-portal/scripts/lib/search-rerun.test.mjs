// QA D1216 guard (round 23): a search that is not re-run.
//
// On /lab, "Nouvelle demande", type "Fatimata": found. Close the dialog,
// reopen it, type "Fatimata" again: no request, no suggestion (and the same
// on prescriptions, referrals, admissions, treatment plans, consultations).
// Each screen's typed-query stream lives as long as the component and ran
//   debounceTime → distinctUntilChanged() → switchMap(server search)
// so the second "Fatimata" equalled the last query and was dropped, while the
// dialog had cleared the suggestions it showed. The same happens without a
// dialog: pick a patient (the list closes), clear the field, type the same
// name — nothing.
//
// Rule: a debounced server search (`switchMap` to a request after
// `debounceTime`) never drops a query because it equals the previous one —
// no `distinctUntilChanged()` between the two. A repeated identical query
// costs one request; a dropped one leaves the user with no results and no
// reason. Streams keyed on something other than typed text (a hospital id)
// are not typed searches and are listed below with why. The shared
// `<app-patient-picker>` follows the rule too.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';
import { balanced } from './js-scan.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/**
 * Pipes that dedupe a value that is NOT a typed query, or that another branch
 * owns this round, each with why.
 */
const ALLOWED = new Map([]);

/**
 * Every `.pipe(…)` of `source` that debounces, then drops a repeat
 * (`distinctUntilChanged()`), then switches to a request. Each hit names the
 * stream the pipe hangs off (`this.patientSearch$.pipe(`).
 */
export function droppedRepeatSearches(source) {
  const out = [];
  for (const m of source.matchAll(/([\w$]+)\s*\.\s*pipe\s*\(/g)) {
    const args = balanced(source, m.index + m[0].length - 1, '(', ')');
    const debounce = args.indexOf('debounceTime(');
    const distinct = args.search(/distinctUntilChanged\(\s*\)/);
    const switched = args.indexOf('switchMap(');
    if (debounce < 0 || distinct < 0 || switched < 0) continue;
    if (!(debounce < distinct && distinct < switched)) continue;
    const line = source.slice(0, m.index).split('\n').length;
    out.push({ stream: m[1], line });
  }
  return out;
}

test('D1216: the lab shape — debounce, distinctUntilChanged, switchMap — is found; without the dedupe it is not', () => {
  const lab = `this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => this.patientService.list(h, q)),
      ).subscribe();`;
  assert.deepEqual(droppedRepeatSearches(lab), [{ stream: 'patientSearch$', line: 1 }]);
  assert.deepEqual(droppedRepeatSearches(lab.replace('distinctUntilChanged(),', '')), []);
});

test('every portal source: a debounced server search never drops a repeated query', () => {
  const offenders = [];
  const seen = new Set();
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const rel = relative(appDir, file).split(sep).join('/');
    for (const hit of droppedRepeatSearches(readFileSync(file, 'utf8'))) {
      const id = `${rel}::${hit.stream}`;
      if (ALLOWED.has(id)) {
        seen.add(id);
        continue;
      }
      offenders.push(`${rel}:${hit.line} ${hit.stream}`);
    }
  }
  assert.deepEqual(offenders, [], 'drop distinctUntilChanged() from the search pipe (QA D1216)');
  const stale = [...ALLOWED.keys()].filter((id) => !seen.has(id));
  assert.deepEqual(stale, [], 'allowed pipes no longer found: delete them');
});
