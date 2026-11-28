/**
 * QA D4194 (r81), class: a counted sentence written with « (s) » instead of a singular and a plural
 * (« 2 écart(s) à examiner », « 1 opération(s) », « 5 flacons restant(s) »), found on the pharmacy's
 * reconciliation, dispensing, alerts and counter sale.
 *
 * The pharmacy and stock strings of every locale hold no « (s) » / « (es) »: a counted sentence has a
 * `KEY_ONE` (singular) beside its `KEY` (plural), chosen by `pluralKey` (shared/i18n/plural-key.ts,
 * Intl.PluralRules), or is worded so that nothing agrees with the count. And a `KEY_ONE` never stands
 * alone, in any locale.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const I18N = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'assets', 'i18n');
const LOCALES = ['fr', 'en', 'es'];
/** The namespaces of the pharmacy and stock pages. */
const PHARMACY = /^(PHARMACY|STOCK_)/;
const BRACKETED_PLURAL = /\((s|es|e|x)\)/i;

const bundle = (locale) => JSON.parse(readFileSync(resolve(I18N, `${locale}.json`), 'utf8'));

function strings(node, path, out = []) {
  if (typeof node === 'string') out.push([path, node]);
  else if (node && typeof node === 'object') {
    for (const [key, value] of Object.entries(node)) strings(value, `${path}.${key}`, out);
  }
  return out;
}

function pharmacyStrings(locale) {
  const all = bundle(locale);
  return Object.keys(all)
    .filter((top) => PHARMACY.test(top))
    .flatMap((top) => strings(all[top], top));
}

for (const locale of LOCALES) {
  test(`${locale}: no pharmacy or stock string writes a bracketed plural`, () => {
    const offenders = pharmacyStrings(locale)
      .filter(([, value]) => BRACKETED_PLURAL.test(value))
      .map(([path, value]) => `${path}: ${value}`);
    assert.deepEqual(offenders, []);
  });

  test(`${locale}: a singular key (_ONE) has its plural beside it`, () => {
    const keys = new Set(pharmacyStrings(locale).map(([path]) => path));
    // The plural is `KEY`, or `KEY_OTHER` / `KEY_MANY` on the pages that predate `pluralKey`.
    const orphans = [...keys].filter((key) => {
      if (!key.endsWith('_ONE')) return false;
      const base = key.slice(0, -4);
      return !['', '_OTHER', '_MANY', '_PLURAL'].some((suffix) => keys.has(base + suffix));
    });
    assert.deepEqual(orphans, []);
  });
}

test('the scan reads the strings it is about', () => {
  const fr = Object.fromEntries(pharmacyStrings('fr'));
  assert.equal(fr['PHARMACY_MONEY.RECON.DIFFERENCES_ONE'], '{{count}} écart à examiner.');
  assert.equal(fr['PHARMACY_MONEY.RECON.DIFFERENCES'], '{{count}} écarts à examiner.');
  assert.match('2 écart(s)', BRACKETED_PLURAL);
});
