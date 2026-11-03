#!/usr/bin/env node
/**
 * QA D1352 / D1354 (round 30): the server now names the refused field of a
 * duplicate isolation precaution (precautionType), a same-bed transfer
 * (toBedId), an over-dispense (quantityDispensed), an adjustment above the
 * received quantity (quantity / initialQuantity / remainingQuantity) and a
 * witness refusal (witnessedBy). The portal marks a `fieldErrors` key through
 * ONE mapper (shared/forms/server-field-errors.ts): a control answers to a key
 * by `data-server-field`, or by its id / `name` with case, dashes and
 * underscores ignored. This test holds each of those screens' controls to the
 * key the server sends, so a renamed control cannot silently go back to a
 * toast alone.
 *
 * The handoff recipient (recipientUserId, nurse-station.html #ho-recipient)
 * is not here: that template belongs to the MAR branch this round
 * (fix/r30-prn-interval); flagged in the round-30 hand-back.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { SRC_DIR } from './template-gate.mjs';

const norm = (s) => s.replace(/[-_]/g, '').toLowerCase();

/** The opening tag of the control with this id, or null. */
function controlTag(html, id) {
  const m = new RegExp(`<(?:input|select|textarea)\\b[^>]*\\bid="${id}"[^>]*>`, 's').exec(html);
  return m ? m[0] : null;
}

/** Whether the mapper's rules 1–2 reach the control for `key`. */
function answersTo(tag, key) {
  const attr = (name) => new RegExp(`\\s${name}="([^"]*)"`).exec(tag)?.[1];
  const keys = (attr('data-server-field') ?? '').split(/\s+/).filter(Boolean);
  if (keys.includes(key)) return true;
  return [attr('id'), attr('name'), attr('formcontrolname')].some(
    (v) => v && norm(v) === norm(key),
  );
}

const EXPECTED = [
  ['bed-board/bed-board.html', 'precaution-type', 'precautionType'],
  ['bed-board/bed-board.html', 'transfer-destination', 'toBedId'],
  ['pharmacy/dispensing.html', 'qtyDispensed', 'quantityDispensed'],
  ['pharmacy/dispensing.html', 'dispWitness', 'witnessedBy'],
  ['pharmacy/goods-receipt.html', 'grQty', 'initialQuantity'],
  ['pharmacy/goods-receipt.html', 'grQty', 'remainingQuantity'],
  ['pharmacy/goods-receipt.html', 'grWitness', 'witnessedBy'],
  ['pharmacy/stock-adjustment.html', 'txQty', 'quantity'],
  ['pharmacy/stock-adjustment.html', 'txWitness', 'witnessedBy'],
  // QA round 31: D1367 (an appointment conflict names startTime), D1365 (a
  // duplicate dose names administrationDate), D1368 (a withdrawal without a
  // reason names withdrawalReason), D1373 (an unknown sex names gender).
  ['appointments/appointment-form.html', 'start', 'startTime'],
  ['appointments/appointment-detail.html', 'reschedStart', 'startTime'],
  ['appointments/appointment-detail.html', 'reschedDate', 'appointmentDate'],
  ['appointments/appointment-detail.html', 'reschedStaff', 'staffId'],
  [
    'patients/medical-history-tab/medical-history-tab.component.html',
    'im-date',
    'administrationDate',
  ],
  ['prescriptions/prescriptions.html', 'rx-withdrawalReason', 'withdrawalReason'],
  ['patients/patient-form.html', 'gender', 'gender'],
  [
    'patients/patient-demographics-edit/patient-demographics-edit.component.html',
    'demoGender',
    'gender',
  ],
];

test('D1352/D1354/R31: each refused field of these screens answers to the key the server names', () => {
  const missing = [];
  for (const [file, id, key] of EXPECTED) {
    const tag = controlTag(readFileSync(resolve(SRC_DIR, file), 'utf8'), id);
    if (!tag) missing.push(`${file}: no control #${id}`);
    else if (!answersTo(tag, key))
      missing.push(`${file}: #${id} does not answer to fieldErrors.${key}`);
  }
  assert.deepEqual(missing, []);
});
