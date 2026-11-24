import { test } from 'node:test';
import assert from 'node:assert/strict';
import { copiedTranslations, presentationFindings } from './presentation-regressions.mjs';

test('rejects copied namespace prose and missing leaves, keeping only the documented SMS acronym', () => {
  assert.deepEqual(
    copiedTranslations(
      { TITLE: 'Delivery', CHANNEL: { SMS: 'SMS', EMAIL: 'Email' }, ERROR: { OFFLINE: 'Offline' } },
      { TITLE: 'Delivery', CHANNEL: { SMS: 'SMS', EMAIL: 'Correo' }, ERROR: {} },
    ),
    ['TITLE', 'ERROR.OFFLINE'],
  );
  assert.deepEqual(copiedTranslations({ TITLE: 'Delivery' }, { TITLE: 'Entrega' }), []);
});

test('guards both received and remaining quantities, including inline templates', () => {
  assert.equal(
    presentationFindings([
      {
        rel: 'inline.ts',
        text: '{{ lot.initialQuantity }} {{ lotUnit(lot) }} / {{ lot.remainingQuantity }} {{ lotUnit(lot) }}',
      },
    ]).length,
    2,
  );
  assert.deepEqual(
    presentationFindings([
      { rel: 'inline.ts', text: '{{ lot.initialQuantity | countUnit: lotUnit(lot) }}' },
    ]),
    [],
  );
});

test('zero is allowed only once matching-record data is known', () => {
  assert.equal(
    presentationFindings([
      {
        rel: 'page.html',
        text: "'PATIENTS.MATCHING_RECORDS' | translate: { count: filtered().length }",
      },
    ]).length,
    1,
  );
  assert.deepEqual(
    presentationFindings([
      {
        rel: 'page.html',
        text: "'PATIENTS.MATCHING_RECORDS' | translate: { count: loading() || loadError() ? '—' : filtered().length }",
      },
    ]),
    [],
  );
});
