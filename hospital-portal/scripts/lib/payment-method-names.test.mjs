// QA D1485 guard: one name per payment method, per language, across the tills (billing, reception,
// the patient's portal), the enum labels and what the server prints on a document. "Paiement mobile"
// at one till and "Mobile Money" at the next read as two ways of paying.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const CODES = [
  'CASH',
  'CREDIT_CARD',
  'DEBIT_CARD',
  'INSURANCE',
  'BANK_TRANSFER',
  'CHECK',
  'CARD',
  'MOBILE_MONEY',
  'OTHER',
];

/** Every object holding payment-method codes: code -> name -> where. */
export function namesByCode(tree) {
  const names = new Map(CODES.map((c) => [c, new Map()]));
  const walk = (node, path) => {
    if (node === null || typeof node !== 'object') return;
    if ('MOBILE_MONEY' in node || ('CARD' in node && 'CASH' in node)) {
      for (const code of CODES) {
        if (typeof node[code] === 'string') {
          const at = names.get(code).get(node[code]) ?? [];
          names.get(code).set(node[code], [...at, path]);
        }
      }
    }
    for (const [k, v] of Object.entries(node)) walk(v, `${path}.${k}`);
  };
  walk(tree, '');
  return names;
}

function serverNames(suffix) {
  const file = join(
    root,
    '..',
    'hospital-core',
    'src',
    'main',
    'resources',
    'i18n',
    `messages-notification${suffix}.properties`,
  );
  const out = new Map();
  for (const m of readFileSync(file, 'utf8').matchAll(
    /^pdf\.payment\.method\.([A-Z_]+)=(.*?)\r?$/gm,
  )) {
    out.set(m[1], m[2]);
  }
  return out;
}

for (const [locale, suffix] of [
  ['en', '_en'],
  ['fr', '_fr'],
  ['es', '_es'],
]) {
  test(`${locale}: one name per payment method, the same as the server's documents`, () => {
    const tree = JSON.parse(
      readFileSync(join(root, 'src', 'assets', 'i18n', `${locale}.json`), 'utf8'),
    );
    const printed = serverNames(suffix);
    const problems = [];
    for (const [code, names] of namesByCode(tree)) {
      if (names.size > 1) problems.push(`${code}: ${[...names.keys()].join(' / ')}`);
      for (const name of names.keys()) {
        if (printed.has(code) && printed.get(code) !== name) {
          problems.push(`${code}: portal "${name}", documents "${printed.get(code)}"`);
        }
      }
    }
    assert.deepEqual(problems, []);
  });
}

test('the scan sees the shape the defect had', () => {
  const names = namesByCode({
    A: { MOBILE_MONEY: 'Paiement mobile' },
    B: { MOBILE_MONEY: 'Mobile Money' },
  });
  assert.equal(names.get('MOBILE_MONEY').size, 2);
});
