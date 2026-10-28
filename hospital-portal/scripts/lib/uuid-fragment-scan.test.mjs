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
    label = order.orderRef.substr(0, 6);`;
  assert.deepEqual(anchors(ts), [
    'slice-call:patientId.substring',
    'slice-call:idValue.slice',
    'slice-call:order.orderRef.substr',
  ]);
});

test('human codes, long slices and words that merely end in "id" pass', () => {
  const text = `{{ encounter.encounterNumber }} {{ patient.mrn }} {{ invoice.invoiceNumber }}
    {{ note.text | slice: 0 : 80 }} {{ row.isValid.slice(0, 8) }} {{ s.paid | slice: 0 : 3 }}
    const fullId = row.id.slice(0, 36);`;
  assert.deepEqual(anchors(text), []);
});
