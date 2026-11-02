// QA D1067 guard: a running gestational age computed for a stored history
// without asking whether the woman has delivered.
//
// The D1005 fix put "Grossesse en cours : 39 SA" on the chart and a running
// TERME on the maternity list from the history's due date alone, so a woman
// delivered the day before still read 39 weeks pregnant. The one way to read
// an ongoing pregnancy off a history is `ongoingPregnancy(history)` in
// maternity/pregnancy-dates.ts, which returns null once `deliveredAt` is set.
// A bare `gestationalAge(` call anywhere else fails here unless it is listed
// with why it is not a stored history.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/** `file::method` → why its gestationalAge call is not a stored history's. */
const ALLOWED = new Map([
  [
    'maternity/maternity.ts::formGestationalAge',
    'the history form previews the term of the dates being typed, before anything is saved',
  ],
]);

function tsFiles(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...tsFiles(full));
    else if (name.endsWith('.ts') && !name.endsWith('.spec.ts')) out.push(full);
  }
  return out;
}

/** Every `gestationalAge(` call in `src`, keyed by the enclosing method name. */
export function callSites(src) {
  const sites = [];
  const lines = src.split('\n');
  let method = '(top level)';
  for (const line of lines) {
    const m =
      /^\s{2}(?:(?:private|protected|public|readonly|static|async)\s+)*(\w+)\s*(?:=\s*\(|\()/.exec(
        line,
      );
    if (m && !/^\s{2}(?:if|for|while|switch|return)\b/.test(line)) method = m[1];
    if (/(?<![\w.])gestationalAge\s*\(/.test(line) && !/function gestationalAge/.test(line)) {
      sites.push(method);
    }
  }
  return sites;
}

test('QA D1067: a gestational age for a stored history goes through ongoingPregnancy', () => {
  const found = [];
  for (const file of tsFiles(appDir)) {
    const rel = relative(appDir, file).split(sep).join('/');
    if (rel === 'maternity/pregnancy-dates.ts') continue;
    for (const method of callSites(readFileSync(file, 'utf8'))) found.push(`${rel}::${method}`);
  }
  assert.deepEqual(
    found.filter((k) => !ALLOWED.has(k)),
    [],
    'use ongoingPregnancy(history) (it checks deliveredAt), or list the site with why',
  );
  assert.deepEqual(
    [...ALLOWED.keys()].filter((k) => !found.includes(k)),
    [],
    'stale ALLOWED entry: delete it',
  );
});

test('QA D1067 guard: the scan sees the shape the defect had', () => {
  const chart = `export class C {
  private loadPregnancy(): void {
    const ga = gestationalAge(dueDate);
  }
}`;
  assert.deepEqual(callSites(chart), ['loadPregnancy']);
  assert.deepEqual(callSites('  load(): void { this.pregnancy.set(ongoingPregnancy(h)); }'), []);
});
