// QA D1178 guard (round 21): a midwife labelled "Médecin".
//
// A visit's provider may be a doctor, a nurse or a midwife (the walk-in,
// check-in and maternity paths all accept her). The tracker card read
// "Médecin : Bibata Yaméogo" and the encounter panel "MÉDECIN" for the
// midwife who held the visit. Every label shown next to a visit's provider
// (`staffName`, `assignedProvider`) must be role-neutral in French and
// Spanish — never the physician's title.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';

const portalDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const appDir = join(portalDir, 'src', 'app');
const locale = (lang) =>
  JSON.parse(readFileSync(join(portalDir, 'src', 'assets', 'i18n', `${lang}.json`), 'utf8'));

/** The physician's title, which a midwife or a nurse is not. */
const PHYSICIAN_ONLY = { fr: /^m[ée]decin\b/i, es: /^m[ée]dic[oa]\b/i };

/** A visit's provider rendered in a template. */
const VISIT_PROVIDER = /\b(?:enc|encounter|item|visit|v|e)\??\.(?:staffName|assignedProvider)\b/;

function lookup(dict, key) {
  return key.split('.').reduce((node, part) => (node == null ? undefined : node[part]), dict);
}

/** Label keys on the lines just before (or on) each visit-provider value. */
export function providerLabelKeys(text) {
  const lines = text.split('\n');
  const keys = new Set();
  lines.forEach((line, i) => {
    if (!VISIT_PROVIDER.test(line)) return;
    for (let k = Math.max(0, i - 3); k <= i; k++) {
      for (const m of lines[k].matchAll(/'([A-Z_]+(?:\.[A-Z_]+)+)'\s*\|\s*translate/g)) {
        if (m[1] !== 'COMMON.COLON') keys.add(m[1]);
      }
    }
  });
  return keys;
}

test('QA D1178: the label next to a visit provider never names a physician in FR or ES', () => {
  const fr = locale('fr');
  const es = locale('es');
  const problems = [];
  let seen = 0;
  for (const { file, text } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const key of providerLabelKeys(text)) {
      seen++;
      for (const [lang, dict] of [
        ['fr', fr],
        ['es', es],
      ]) {
        const value = lookup(dict, key);
        if (typeof value === 'string' && PHYSICIAN_ONLY[lang].test(value.trim())) {
          problems.push(`${rel}: ${key} reads "${value}" in ${lang}`);
        }
      }
    }
  }
  assert.ok(seen >= 2, `the scan must see the tracker and encounter labels, saw ${seen}`);
  assert.deepEqual(problems, []);
});

test('QA D1178 guard: the scan finds the label of the tracker card', () => {
  const card = `<span class="field-label"
    >{{ 'TRACKER.PROVIDER' | translate }}{{ 'COMMON.COLON' | translate }}</span>
    {{ item.assignedProvider }}`;
  assert.deepEqual([...providerLabelKeys(card)], ['TRACKER.PROVIDER']);
});
