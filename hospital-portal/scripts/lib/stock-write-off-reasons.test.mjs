// QA D1243 guard (round 25): a stock adjustment reason the portal offers
// that the portal and the server classify differently.
//
// A positive "Détérioration" added two units. The server now refuses a
// write-off (damage, expiry, loss, theft, breakage) that adds stock
// (`stockTransaction.writeOff.mustRemove` on `quantity`,
// service/pharmacy/StockWriteOffReason.java), and the adjustment form refuses
// it first (WRITE_OFF_REASON_CODES in pharmacy/stock-adjustment.ts). Every
// code the form's select offers must be classified the same way on both
// sides: a write-off on the server is a write-off in the form, and a code the
// server reads as two-way (a count variance) is not.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const FORM = join(here, '..', '..', 'src', 'app', 'pharmacy', 'stock-adjustment.ts');
const SERVER = join(
  here,
  '..',
  '..',
  '..',
  'hospital-core',
  'src',
  'main',
  'java',
  'com',
  'example',
  'hms',
  'service',
  'pharmacy',
  'StockWriteOffReason.java',
);

/**
 * The server's classification, as #1018 (branch fix/r25-mar-cds-pharmacy-billing)
 * ships it. When that file is in the tree it is read and must agree with this
 * copy, so a server change fails here in the same build.
 */
const SERVER_WRITE_OFF_CODES = [
  'DAMAGE',
  'DAMAGED',
  'EXPIRY_WRITE_OFF',
  'EXPIRED',
  'EXPIRY',
  'WRITE_OFF',
  'LOSS',
  'LOST',
  'THEFT',
  'STOLEN',
  'BREAKAGE',
  'BROKEN',
];
const SERVER_TWO_WAY_CODES = ['CYCLE_COUNT_VARIANCE', 'CONTROLLED_DISCREPANCY'];

/** The string literals of a `const NAME = [ … ]` or `new Set([ … ])` in a TS source. */
export function tsCodeList(ts, name) {
  const m = new RegExp(`const ${name}\\b[^=]*=\\s*(?:new Set\\()?\\[([^\\]]*)\\]`).exec(ts);
  return m ? [...m[1].matchAll(/'([A-Z_]+)'/g)].map((x) => x[1]) : [];
}

/** The upper-case codes of a `Set.of(…)` assigned to `name` in a Java source (comments removed). */
export function javaSetCodes(java, name) {
  const code = java.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
  const m = new RegExp(`\\b${name}\\s*=\\s*Set\\.of\\(([^;]*)\\);`).exec(code);
  return m ? [...m[1].matchAll(/"([A-Z_]+)"/g)].map((x) => x[1]) : [];
}

test('QA D1243: every adjustment reason the form offers is classified as the server classifies it', () => {
  const ts = readFileSync(FORM, 'utf8');
  const offered = tsCodeList(ts, 'ADJUSTMENT_REASON_CODES');
  const writeOffs = tsCodeList(ts, 'WRITE_OFF_REASON_CODES');
  assert.ok(offered.length >= 4, `the offered reasons must be read, saw ${offered}`);
  for (const code of offered) {
    assert.ok(
      SERVER_WRITE_OFF_CODES.includes(code) || SERVER_TWO_WAY_CODES.includes(code),
      `${code} is offered but the server does not classify it`,
    );
  }
  assert.deepEqual(
    [...writeOffs].sort(),
    offered.filter((c) => SERVER_WRITE_OFF_CODES.includes(c)).sort(),
    'the form refuses a positive quantity for exactly the offered write-offs',
  );
  assert.match(ts, /WRITE_OFF_KEY = 'PHARMACY\.WRITE_OFF_REMOVES_ONLY'/);
  // Changed in round 28 (QA D1306): every typed refusal marks its own field
  // through one map; the write-off key still marks the quantity.
  assert.match(ts, /\[WRITE_OFF_KEY\]: 'txQty'/, 'refused on the quantity');
  assert.match(
    ts,
    /refuseFields\(root, fieldId \? \[fieldId\] : \[\], TX_FIELD_IDS, message\)/,
    'refused on the quantity',
  );
});

test('QA D1243: the copy of the server classification is the server file', () => {
  if (!existsSync(SERVER)) {
    // Before #1018 merges into the combined branch, the server file is not in
    // this tree; the copy above is what the form is held to.
    assert.ok(SERVER_WRITE_OFF_CODES.includes('DAMAGE'));
    return;
  }
  const java = readFileSync(SERVER, 'utf8');
  assert.match(java, /MUST_REMOVE_KEY = "stockTransaction\.writeOff\.mustRemove"/);
  const writeOff = javaSetCodes(java, 'WRITE_OFF_CODES');
  for (const code of SERVER_WRITE_OFF_CODES) assert.ok(writeOff.includes(code), code);
  assert.deepEqual(javaSetCodes(java, 'TWO_WAY').sort(), [...SERVER_TWO_WAY_CODES].sort());
});

test('QA D1243 guard: the parsers read the lists', () => {
  assert.deepEqual(tsCodeList("const X = [\n  'A',\n  'B_C',\n] as const;", 'X'), ['A', 'B_C']);
  assert.deepEqual(tsCodeList("const Y: ReadonlySet<string> = new Set(['D', 'E']);", 'Y'), [
    'D',
    'E',
  ]);
  assert.deepEqual(
    javaSetCodes('static final Set<String> TWO_WAY = Set.of("A", // x\n "B");', 'TWO_WAY'),
    ['A', 'B'],
  );
});
