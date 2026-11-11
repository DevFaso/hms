// QA D964 then D1029 guard: a pharmacy screen picks a lot first expired,
// first out, through src/app/pharmacy/fefo-lots.ts.
//
// Round 16 fixed the dispensing screen's pre-selection to "the shortest-dated
// lot that covers the quantity", and round 17 found the hole that rule left:
// 130 tablets out of the 2028 lot while 120 sat in the 2027 lot, no reason
// asked. Every screen that chooses a lot by "covers the quantity"
// (`remainingQuantity >= qty`) or compares two lots' expiry dates by hand is
// re-deriving the rule, and that is how it drifted. This fails on either shape
// anywhere in the app outside the shared helper.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const HELPER = 'pharmacy/fefo-lots.ts';

/** The lot-choosing shapes in one source file. */
export function fefoOffenders(source, file = 'source') {
  const offenders = [];
  const at = (index) => `${file}:${source.slice(0, index).split('\n').length}`;
  for (const m of source.matchAll(/\.remainingQuantity\s*>=\s*\w/g)) {
    offenders.push(
      `${at(m.index)} lot chosen by "covers the quantity": use fefoFirstLot / fefoAllocation`,
    );
  }
  for (const m of source.matchAll(/\.expiryDate\s*<\s*\w+\??\.expiryDate\b/g)) {
    offenders.push(`${at(m.index)} expiry dates compared by hand: use earlierLotWithStock`);
  }
  return offenders;
}

test('QA D1029: every lot choice goes through the shared FEFO helper', () => {
  const files = walk(appDir, ['.ts']).filter((f) => !f.endsWith('.spec.ts'));
  assert.ok(files.length > 100, `scanned ${files.length} files`);
  const offenders = files
    .map((f) => [f, relative(appDir, f).replaceAll('\\', '/')])
    .filter(([, rel]) => rel !== HELPER)
    .flatMap(([f, rel]) => fefoOffenders(readFileSync(f, 'utf8'), rel));
  assert.deepEqual(offenders, []);
});

test('QA D1029 guard: the scan sees the shapes the defect had', () => {
  assert.equal(
    fefoOffenders('lots.find((lot) => lot.remainingQuantity >= qty) ?? lots[0]').length,
    1,
  );
  assert.equal(fefoOffenders('l.expiryDate < chosen.expiryDate').length, 1);
  assert.deepEqual(fefoOffenders('fefoFirstLot(this.usableLots())'), []);
});
