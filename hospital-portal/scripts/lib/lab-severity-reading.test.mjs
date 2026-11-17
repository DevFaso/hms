// QA D969 guard: a lab result's grade (severityFlag) is read in one place.
//
// The validation queue kept its own list of flags and read ABNORMAL as "not
// graded", so a positive malaria test showed "Non évalué" there and "Anormal"
// on /lab-results. Every screen now asks lab/shared/lab-severity.ts. This
// fails when any portal source compares severityFlag with a literal, or
// switches on it, outside that module.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const SHARED = 'lab/shared/lab-severity.ts';

/** Literal readings of severityFlag in one source text. */
export function ownSeverityReadings(text, file = 'source') {
  const offenders = [];
  const patterns = [
    /severityFlag\)?\s*[!=]==?\s*'/g,
    /'\s*[!=]==?\s*[\w.?]*severityFlag\b/g,
    /switch\s*\(\s*[\w.?]*severityFlag/g,
    /\[[^\]]*'(CRITICAL|ABNORMAL)'[^\]]*\]\s*\.includes\(\s*[\w.?]*severityFlag/g,
  ];
  // The defect's own shape: a flag list of its own, in a file that grades severityFlag.
  if (/severityFlag/.test(text)) {
    patterns.push(
      /\[\s*'(CRITICAL|HIGH|LOW|NORMAL|ABNORMAL)'(\s*,\s*'[A-Z_]+')+\s*\]\s*\.includes\(/g,
    );
  }
  for (const pattern of patterns) {
    for (const m of text.matchAll(pattern)) {
      offenders.push(`${file}:${text.slice(0, m.index).split('\n').length} ${m[0].trim()}`);
    }
  }
  return offenders;
}

test('QA D969: no screen reads severityFlag with its own flag list', () => {
  const offenders = walk(appDir, ['.ts', '.html'])
    .filter((f) => !f.endsWith('.spec.ts'))
    .map((f) => [f, relative(appDir, f).split('\\').join('/')])
    .filter(([, rel]) => rel !== SHARED)
    .flatMap(([f, rel]) => ownSeverityReadings(readFileSync(f, 'utf8'), rel));
  assert.deepEqual(offenders, []);
});

test('QA D969 guard: the scan sees the shape the defect had', () => {
  assert.equal(ownSeverityReadings("if (row.severityFlag === 'CRITICAL') return 'x';").length, 1);
  assert.equal(ownSeverityReadings('switch (row.severityFlag) { }').length, 1);
  const old =
    "const flag = (r.severityFlag ?? '').toUpperCase(); " +
    "return ['CRITICAL', 'LOW', 'HIGH', 'NORMAL'].includes(flag) ? flag : 'UNSPECIFIED';";
  assert.equal(ownSeverityReadings(old).length, 1);
  assert.equal(
    ownSeverityReadings("return labSeverityFlag({ severityFlag: r.severityFlag }) === 'CRITICAL';")
      .length,
    0,
  );
});
