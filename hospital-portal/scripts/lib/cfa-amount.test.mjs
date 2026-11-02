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
  // QA D1294 (round 27): every caller names the wording for an empty field,
  // and a price never borrows the payment's ("Enter a payment amount…").
  for (const m of source.matchAll(
    /cfaAmountMessageKey\(([^,()]*(?:\([^()]*\))?[^,()]*)(?:,\s*([^)]*))?\)/g,
  )) {
    const [problem, key] = [m[1].trim(), (m[2] ?? '').trim()];
    if (key === '') {
      offenders.push(`${at(m.index)} cfaAmountMessageKey without the field's own wording`);
    } else if (
      /price/i.test(problem) &&
      /PAYMENT_AMOUNT_REQUIRED|CFA_AMOUNT_REQUIRED_KEY/.test(key)
    ) {
      offenders.push(`${at(m.index)} a price refused with the payment wording`);
    }
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
  assert.deepEqual(cfaAmountOffenders('cfaAmountMessageKey(problem, CFA_AMOUNT_REQUIRED_KEY)'), []);
});

test('QA D1294 guard: the scan sees the blank unit price that spoke of a payment', () => {
  // The shape round 27 found: the default (payment) wording on a line price.
  assert.equal(
    cfaAmountOffenders('this.translate.instant(cfaAmountMessageKey(priceProblem))').length,
    1,
  );
  assert.equal(
    cfaAmountOffenders('cfaAmountMessageKey(priceProblem, CFA_AMOUNT_REQUIRED_KEY)').length,
    1,
  );
  assert.deepEqual(
    cfaAmountOffenders("cfaAmountMessageKey(priceProblem, 'BILLING.LINE_UNIT_PRICE_REQUIRED')"),
    [],
  );
  assert.deepEqual(
    cfaAmountOffenders("cfaAmountMessageKey(amountProblem ?? 'required', CFA_AMOUNT_REQUIRED_KEY)"),
    [],
  );
});
