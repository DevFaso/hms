// QA D1222 guard (round 23): a row's status pill that reads as a plural.
//
// The lab worklist showed "Pendientes de publicación" on the row of ONE
// result: the pill reused the filter chip's count label. Rule, every
// template: a key used both as a row's status pill and as a count label
// (lib/row-pill-keys.mjs) must not be a plural in a language that inflects
// for number — its first word in es / fr is a plural ("Pendientes") where
// the English one is not. Fix: a dedicated singular key for the row
// (LAB_RESULTS.ROW_PENDING_RELEASE). Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { sharedRowAndCountKeys } from './row-pill-keys.mjs';
import { loadTemplates } from './template-gate.mjs';

const I18N = resolve(import.meta.dirname, '..', '..', 'src', 'assets', 'i18n');
const LOCALES = Object.fromEntries(
  ['en', 'fr', 'es'].map((l) => [l, JSON.parse(readFileSync(resolve(I18N, `${l}.json`), 'utf8'))]),
);
const value = (locale, key) => key.split('.').reduce((node, part) => node?.[part], LOCALES[locale]);
const firstWord = (text) => (typeof text === 'string' ? text.trim().split(/\s+/)[0] : '');

/** Is the row pill's text a plural in es or fr where English is not? */
export function readsPlural(key) {
  const en = firstWord(value('en', key));
  if (/s$/i.test(en)) return false;
  return ['es', 'fr'].some((l) => /[^s]s$/i.test(firstWord(value(l, key))));
}

test('D1222: the shape — a row pill sharing the filter chip key — is found', () => {
  const html = `<button class="filter-chip">{{ 'LAB_RESULTS.PENDING_RELEASE' | translate }} ({{ n }})</button>
@for (r of rows; track r.id) { <span class="status-badge status-pending">{{ 'LAB_RESULTS.PENDING_RELEASE' | translate }}</span> }`;
  assert.deepEqual(sharedRowAndCountKeys(html), ['LAB_RESULTS.PENDING_RELEASE']);
  assert.equal(readsPlural('LAB_RESULTS.PENDING_RELEASE'), true);
  assert.equal(readsPlural('LAB_RESULTS.ROW_PENDING_RELEASE'), false);
});

test('every template: no row status pill reads as a plural (allowlist: none)', () => {
  const offenders = [];
  let shared = 0;
  for (const { rel, text } of loadTemplates()) {
    for (const key of sharedRowAndCountKeys(text)) {
      shared++;
      if (readsPlural(key))
        offenders.push(`${rel} ${key}: es "${value('es', key)}", fr "${value('fr', key)}"`);
    }
  }
  assert.ok(shared >= 3, `only ${shared} shared row/count keys found: the scan lost its shape`);
  assert.deepEqual(offenders, []);
});
