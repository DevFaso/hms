#!/usr/bin/env node
/**
 * The identifier-fragment scan (QA D992). The failing cases are the triage
 * form's markup as reported and the component-side spellings; the passing
 * cases are human codes and slices of things that are not ids.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { uuidFragmentFindings } from './uuid-fragment-scan.mjs';

const anchors = (text) => uuidFragmentFindings(text).map((f) => `${f.rule}:${f.anchor}`);

test('D992: encounter.id | slice: 0 : 8 is a UUID fragment on screen', () => {
  const html = `<span class="value">{{ encounter.id | slice: 0 : 8 }}</span>
    <td>{{ c.patientId.substring(0, 8) }}…</td>`;
  assert.deepEqual(anchors(html), [
    'slice-pipe:encounter.id | slice',
    'slice-call:c.patientId.substring',
  ]);
});

test('the component spellings fail too', () => {
  const ts = `return this.names.get(patientId) || \`\${patientId.substring(0, 8)}…\`;
    const short = idValue ? idValue.slice(0, 8) : '';
    label = order.orderRef.substr(0, 6);
    short = visit.id?.slice(0, 8); other = encounter.id!.substring(0, 8);`;
  assert.deepEqual(anchors(ts), [
    'slice-call:patientId.substring',
    'slice-call:idValue.slice',
    'slice-call:order.orderRef.substr',
    'slice-call:visit.id.slice',
    'slice-call:encounter.id.substring',
  ]);
  assert.deepEqual(anchors(`{{ visit.id! | slice: 0 : 8 }}`), ['slice-pipe:visit.id | slice']);
});

test('the LAST characters of an id are the same fragment', () => {
  const text = `Visite {{ encounter.id | slice: -8 }} {{ row.patientId.slice(-6) }}
    const tail = order.id.substring(order.id.length - 8);`;
  assert.deepEqual(anchors(text), [
    'slice-pipe:encounter.id | slice',
    'slice-call:row.patientId.slice',
    'slice-call:order.id.substring',
  ]);
});

test('human codes, long slices and words that merely end in "id" pass', () => {
  const text = `{{ encounter.encounterNumber }} {{ patient.mrn }} {{ invoice.invoiceNumber }}
    {{ note.text | slice: 0 : 80 }} {{ row.isValid.slice(0, 8) }} {{ s.paid | slice: 0 : 3 }}
    const fullId = row.id.slice(0, 36);`;
  assert.deepEqual(anchors(text), []);
});

test('an identifier that ENDS in Id is an id even when its last letters spell rid/did/lid', () => {
  const text = `{{ encounterId | slice: 0 : 8 }} {{ row.userId.slice(0, 8) }} {{ selectedStaff()!.hospitalId | slice: 0 : 8 }}
    const a = orderId.substring(0, 8), b = bedId.slice(0, 8), c = recordId.slice(0, 8);`;
  assert.equal(anchors(text).length, 6);
});

test("the first UUID group (id.split('-')[0]) and a parenthesised id are the same fragment", () => {
  const html = `{{ order.id.split('-')[0] }} {{ (row.patientId).slice(0, 8) }}
    {{ name.split('-')[0] }} {{ isValid.split('-')[0] }}`;
  assert.deepEqual(
    uuidFragmentFindings(html).map((f) => `${f.rule}:${f.anchor}`),
    ['slice-call:(row.patientId.slice', 'slice-call:order.id.split'],
  );
});

test('QA D1032: a slice taken after the dashes are stripped is a UUID fragment, whatever the receiver', () => {
  const text = `return (id ?? '').replace(/-/g, '').substring(0, 10).toUpperCase();
    const a = ref.replaceAll('-', '').slice(0, 8);
    const b = row.key.split("-").join("").toUpperCase().substr(0, 12);
    const whole = uuid.replace(/-/g, '').slice(0, 40);
    const phone = phone.replace(/ /g, '').slice(0, 8);`;
  assert.deepEqual(
    uuidFragmentFindings(text).map((f) => f.rule),
    ['strip-slice', 'strip-slice', 'strip-slice'],
  );
});
