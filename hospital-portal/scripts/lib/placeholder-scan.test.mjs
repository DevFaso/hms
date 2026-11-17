#!/usr/bin/env node
/**
 * The placeholder gate, held to the exact text QA D837 and D867 reported:
 * each case is a value or a template line that shipped, and must fail.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { exampleOf, literalPlaceholders, localeProblems } from './placeholder-scan.mjs';

const en = new Map([
  ['PHARMACY.UNIT_CODE_PLACEHOLDER', 'e.g., TABLET'],
  ['PHARMACY.SUPPLIER_PLACEHOLDER', 'e.g., CAMEG'],
  ['BEDS.WARD_CODE_PLACEHOLDER', 'e.g. MAT01'],
  ['REGION_POLICY.FIELD.EXPORT_PLACEHOLDER', 'e.g. GDPR_PORTABILITY'],
  ['PATIENTS.PHONE_PLACEHOLDER', 'e.g. +226 70 12 34 56'],
]);

test('D837: the literal placeholders that shipped are refused', () => {
  const template = [
    '<input id="defLoincCode" placeholder="e.g. 718-7" />',
    '<input id="atcCode" placeholder="e.g., J01CA04" />',
    '<input name="strength" placeholder="ex. 500" />',
    '<input type="tel" placeholder="+1 (555) 000-0000" />',
  ].join('\n');
  const hits = literalPlaceholders(template);
  assert.deepEqual(
    hits.map((h) => [h.line, h.value]),
    [
      [1, 'e.g. 718-7'],
      [2, 'e.g., J01CA04'],
      [3, 'ex. 500'],
      [4, '+1 (555) 000-0000'],
    ],
  );
});

test('D837: a translated placeholder, and a technical sample, pass', () => {
  const template = [
    `<input [placeholder]="'LAB_TEST_CONFIG.LOINC_PLACEHOLDER' | translate" />`,
    `<input placeholder="{{ 'AUDIT_SEARCH.FILTER.USER_NAME_PLACEHOLDER' | translate }}" />`,
    '<input placeholder="https://" />',
    '<input placeholder="mg" />',
    '<input [attr.placeholder]="hint()" />',
  ].join('\n');
  assert.deepEqual(literalPlaceholders(template), []);
});

test('D837: French keeping the English example word or a US phone is refused', () => {
  const fr = new Map([
    ['PHARMACY.UNIT_CODE_PLACEHOLDER', 'ex. : TABLET'],
    ['PATIENTS.PHONE_PLACEHOLDER', 'ex. (555) 123-4567'],
    ['X.HINT', 'e.g. 12'],
  ]);
  assert.deepEqual(
    localeProblems('fr', fr, en).map((h) => h.key),
    ['PHARMACY.UNIT_CODE_PLACEHOLDER', 'PATIENTS.PHONE_PLACEHOLDER', 'X.HINT'],
  );
});

test('D837: a code, a translated word, and a pinned proper name pass', () => {
  const fr = new Map([
    ['PHARMACY.UNIT_CODE_PLACEHOLDER', 'ex. : comprimé'],
    ['BEDS.WARD_CODE_PLACEHOLDER', 'ex. MAT01'],
    ['REGION_POLICY.FIELD.EXPORT_PLACEHOLDER', 'ex. GDPR_PORTABILITY'],
    ['PATIENTS.PHONE_PLACEHOLDER', 'ex. +226 70 12 34 56'],
    ['PHARMACY.SUPPLIER_PLACEHOLDER', 'ex. : CAMEG'],
  ]);
  assert.deepEqual(
    localeProblems('fr', fr, en, { 'PHARMACY.SUPPLIER_PLACEHOLDER': 'ex. : CAMEG' }),
    [],
  );
  // The pin is on the exact text: an unpinned capitalised English example still fails.
  assert.equal(localeProblems('fr', fr, en).length, 1);
});

test('D867: help text naming a Java class is refused in every language', () => {
  const shipped = new Map([
    [
      'REGION_POLICY.FIELD.DEPLOYMENT_DISABLED_HELP',
      'Désactivé — aucun TenantProvisioningClient distant n’est branché sur ce déploiement.',
    ],
  ]);
  const hits = localeProblems('fr', shipped, new Map());
  assert.equal(hits.length, 1);
  assert.match(hits[0].reason, /TenantProvisioningClient/);
  assert.equal(
    localeProblems(
      'en',
      new Map([['K', 'Register a real TenantProvisioningClient bean.']]),
      new Map(),
    ).length,
    1,
  );
  // An ordinary capitalised word or product name is not a Java type.
  assert.deepEqual(
    localeProblems('en', new Map([['K', 'Open WhatsApp or JavaScript settings.']]), new Map()),
    [],
  );
});

test('exampleOf strips the marker in each language', () => {
  assert.equal(exampleOf('e.g., TABLET'), 'TABLET');
  assert.equal(exampleOf('ex. : comprimé'), 'comprimé');
  assert.equal(exampleOf('ej.: comprimido'), 'comprimido');
  assert.equal(exampleOf('p. ej. ENC-…'), 'ENC-…');
});
