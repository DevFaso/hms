// QA D1071 guard: an invoice's icon and colour come from its status, through
// src/app/shared/invoice-tone.ts.
//
// The patient's billing page picked its icon with `isPayable(inv) ? 'pending'
// : 'check_circle'` and hard-coded the two colours beside it, so every
// invoice that was not payable, a cancelled one included, wore the green
// "paid" check. Any billing or invoice screen that chooses an icon or a colour
// with a ternary of its own is re-deriving the rule; this fails on that shape
// anywhere outside the shared helper.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const HELPER = 'shared/invoice-tone.ts';

/** A file about invoices: its path says billing/invoice, or it binds an `inv`/`invoice` variable. */
function aboutInvoices(rel, source) {
  return /billing|invoice/i.test(rel) || /\b(?:inv|invoice)\s*(?:\.|\))/.test(source);
}

/** The icon/colour choices an invoice screen makes by hand. */
export function invoiceToneOffenders(source, file = 'source') {
  const offenders = [];
  const at = (index) => `${file}:${source.slice(0, index).split('\n').length}`;
  // An icon picked by a ternary on an invoice that yields the paid check.
  for (const m of source.matchAll(/\{\{([^}]*)\}\}/g)) {
    const expr = m[1];
    if (/'check_circle'/.test(expr) && /\?/.test(expr) && /isPayable|\binv\b|invoice/i.test(expr)) {
      offenders.push(`${at(m.index)} invoice icon chosen by hand: use invoiceTone()`);
    }
  }
  // A colour bound from "payable or not".
  for (const m of source.matchAll(/\[style\.(?:background|color)\]="isPayable\(/g)) {
    offenders.push(
      `${at(m.index)} invoice colour chosen by hand: use invoiceTone() and [data-tone]`,
    );
  }
  return offenders;
}

test('QA D1071: every invoice icon and tone goes through invoiceTone()', () => {
  const files = walk(appDir, ['.html', '.ts']).filter((f) => !f.endsWith('.spec.ts'));
  assert.ok(files.length > 100, `scanned ${files.length} files`);
  const offenders = files
    .map((f) => [f, relative(appDir, f).replaceAll('\\', '/')])
    .filter(([, rel]) => rel !== HELPER)
    .map(([f, rel]) => [readFileSync(f, 'utf8'), rel])
    .filter(([source, rel]) => aboutInvoices(rel, source))
    .flatMap(([source, rel]) => invoiceToneOffenders(source, rel));
  assert.deepEqual(offenders, []);
});

test('QA D1071 guard: the scan sees the shapes the defect had', () => {
  assert.equal(invoiceToneOffenders("{{ isPayable(inv) ? 'pending' : 'check_circle' }}").length, 1);
  assert.equal(
    invoiceToneOffenders(`[style.background]="isPayable(inv) ? '#fef3c7' : '#d1fae5'"`).length,
    1,
  );
  assert.deepEqual(invoiceToneOffenders('{{ tone.icon }}'), []);
});
