// QA D1022 (class guard): money taken from a patient at a staff screen comes
// with a receipt. Check-in handed the copay receipt over (D975); collecting
// the same patient's balance from "Paiements en attente" answered "Payment
// recorded" and nothing else. Every component that takes a payment (a call to
// `.recordPayment(` or `.checkInPatient(`) must also fetch a receipt
// (`getReceiptPdf(`) — the staff billing page offers it on each payment row.
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { componentSources } from './component-sources.mjs';

/** A real call (a receiver before the dot), not a word in a comment. */
const RECORDS_PAYMENT = /^(?!\s*(?:\/\/|\*)).*\.\s*recordPayment\s*\(/m;
const CHECKS_IN = /^(?!\s*(?:\/\/|\*)).*\.\s*checkInPatient\s*\(/m;

/**
 * Takes money: records a payment, or checks in with a copay (the appointment
 * page's plain check-in sends no copay and takes none: not a payment screen).
 */
function takesPayment(text) {
  return RECORDS_PAYMENT.test(text) || (CHECKS_IN.test(text) && /copay/i.test(text));
}

export function deskPaymentsWithoutReceipt(sources) {
  return sources
    .filter(({ file }) => !file.endsWith('.service.ts'))
    .filter(({ text }) => takesPayment(text))
    .filter(({ text }) => !text.includes('getReceiptPdf('))
    .map(({ file }) => file);
}

test('QA D1022: every staff screen that takes a payment hands over its receipt', () => {
  const sources = componentSources();
  const takers = sources.filter(
    ({ file, text }) => !file.endsWith('.service.ts') && takesPayment(text),
  );
  assert.ok(takers.length >= 3, `payment screens found: ${takers.map((t) => t.file).join(', ')}`);
  assert.deepEqual(deskPaymentsWithoutReceipt(sources), []);
});

test('the scan sees a payment without a receipt and ignores comments', () => {
  assert.deepEqual(
    deskPaymentsWithoutReceipt([
      { file: 'a.ts', text: 'this.receptionService.recordPayment(id, 5).subscribe();' },
      { file: 'b.ts', text: '// ReceptionServiceImpl.checkInPatient refuses it\nconst x = 1;' },
      { file: 'c.ts', text: 'this.svc.recordPayment(id, 5); this.billing.getReceiptPdf(id, p);' },
      { file: 'd.ts', text: 'this.svc.checkInPatient({ appointmentId }).subscribe();' },
      { file: 'e.ts', text: 'this.svc.checkInPatient({ copayAmount: 500 }).subscribe();' },
    ]),
    ['a.ts', 'e.ts'],
  );
});
