import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  consentScopeFindings,
  copiedTranslations,
  deliveryTranslationFindings,
  presentationFindings,
} from './presentation-regressions.mjs';

test('every locale names the full scope of both shared consent categories', () => {
  for (const [language, all] of [
    ['en', 'all messages'],
    ['fr', 'tous les messages'],
    ['es', 'todos los mensajes'],
  ]) {
    assert.equal(
      consentScopeFindings(
        {
          NOTIFICATIONS: {
            TYPE_APPOINTMENT_REMINDER: 'Reminder',
            TYPE_MEDICATION_REFILL: 'Refill',
          },
        },
        language,
      ).length,
      2,
    );
    assert.deepEqual(
      consentScopeFindings(
        { NOTIFICATIONS: { TYPE_APPOINTMENT_REMINDER: all, TYPE_MEDICATION_REFILL: all } },
        language,
      ),
      [],
    );
  }
});

test('delivery translations include the navigation entry as well as every namespace leaf', () => {
  const en = {
    PATIENT_NOTIFICATIONS: { TITLE: 'Delivery' },
    NAV: { PATIENT_NOTIFICATIONS: 'Delivery' },
  };
  const translated = {
    PATIENT_NOTIFICATIONS: { TITLE: 'Entrega' },
    NAV: { PATIENT_NOTIFICATIONS: 'Delivery' },
  };
  assert.deepEqual(deliveryTranslationFindings(en, translated), [
    'es.NAV.PATIENT_NOTIFICATIONS: missing translation or copied English',
  ]);
  translated.NAV.PATIENT_NOTIFICATIONS = 'Entrega';
  assert.deepEqual(deliveryTranslationFindings(en, translated), []);
});

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

test('an invoice row uses its stored specific category and keeps the legacy fallback explicit', () => {
  assert.equal(
    presentationFindings([
      { rel: 'invoice.html', text: "{{ it.itemCategory | enumLabel: 'invoiceItemCategory' }}" },
    ]).length,
    1,
  );
  assert.deepEqual(
    presentationFindings([
      {
        rel: 'invoice.html',
        text: "{{ it.coverageCategory ? (it.coverageCategory | enumLabel: 'priceListCategory') : (it.itemCategory | enumLabel: 'invoiceItemCategory') }}",
      },
    ]),
    [],
  );
});
