// QA D1089 guard: a typed franc CFA amount is judged by src/app/shared/cfa-amount.ts.
//
// The waiver form tested "empty, zero or with decimals" in one condition and
// answered all three with "francs have no cents: type an amount without
// decimals", so an empty field got the decimals message; the patient's pay
// dialog did nothing at all on an empty amount. The decimals message is
// resolved only by the shared helper, which asks for an amount first; a
// screen that names the key itself is re-deriving the rule.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const HELPER = 'shared/cfa-amount.ts';

export function cfaAmountOffenders(source, file = 'source') {
  const offenders = [];
  const at = (index) => `${file}:${source.slice(0, index).split('\n').length}`;
  for (const m of source.matchAll(/['"`]BILLING\.PAYMENT_WHOLE_AMOUNT['"`]/g)) {
    offenders.push(
      `${at(m.index)} amount judged by hand: use cfaAmountProblem / cfaAmountMessageKey`,
    );
  }
  return offenders;
}

test('QA D1089: every typed amount is judged by the shared helper', () => {
  const files = walk(appDir, ['.ts', '.html']).filter((f) => !f.endsWith('.spec.ts'));
  assert.ok(files.length > 100, `scanned ${files.length} files`);
  const offenders = files
    .map((f) => [f, relative(appDir, f).replaceAll('\\', '/')])
    .filter(([, rel]) => rel !== HELPER)
    .flatMap(([f, rel]) => cfaAmountOffenders(readFileSync(f, 'utf8'), rel));
  assert.deepEqual(offenders, []);
});

test('QA D1089 guard: the scan sees the shape the defect had', () => {
  assert.equal(
    cfaAmountOffenders("this.toast.error(this.translate.instant('BILLING.PAYMENT_WHOLE_AMOUNT'));")
      .length,
    1,
  );
  assert.deepEqual(cfaAmountOffenders('cfaAmountMessageKey(problem)'), []);
});
