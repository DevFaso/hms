// QA D1068 guard: a free-text field a maternity form saves that no screen ever
// shows again.
//
// Round 18: the midwife typed postpartum "Notes cliniques", the newborn exam
// notes, partograph and risk notes; each was stored (the 201 echoed it) and no
// table, summary or chart printed it, so the handover never saw them. The
// delivery summary also dropped the placenta and the uterotonic given.
//
// For every `[(ngModel)]="<x>Form.<field>"` bound in a maternity template,
// where <field> is free text or a clinical fact (notes, appearance, reasons,
// drugs given, placenta…), some maternity template must read `.<field>` off a
// saved record — any receiver that is not a form. A field whose value is read
// elsewhere on purpose (a draft, a reason shown in an audit trail) is listed
// below with why; a listed field that becomes shown, or disappears, fails.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const maternityDir = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'src',
  'app',
  'maternity',
);

/** Field names that carry what a clinician wrote or a clinical fact worth handing over. */
export const FREE_TEXT_FIELD =
  /(?:[nN]otes?|Reason|Appearance|Summary|Description|Comments|Recommendations|Interpretation|Wishes|Instructions|Indication|drugsGiven|uterotonicGiven|placentaComplete|placentaDeliveredAt)$/;

/** Form fields excused, `<form>.<field>` → why it is not read back on a maternity screen. */
const NOT_READ_BACK = new Map([
  [
    'startForm.recentDeliveryOverrideReason',
    'a confirmation reason for the recent-delivery override, kept on the audit trail (not a clinical note)',
  ],
  [
    'deliveryForm.recentDeliveryOverrideReason',
    'a confirmation reason for the recent-delivery override, kept on the audit trail (not a clinical note)',
  ],
]);

/** Every `[(ngModel)]="xForm.a.b"` binding: { form, field }. */
export function formFields(html) {
  const out = [];
  for (const m of html.matchAll(/\[\(ngModel\)\]="(\w+Form)\.(?:\w+!?\.)*(\w+)"/g)) {
    out.push({ form: m[1], field: m[2] });
  }
  return out;
}

/**
 * The distinct non-form receivers that read `.field` (`record`, `entry`, `o`…).
 * Each form binding a field needs its own reader: `notes` bound by the
 * delivery form and the partograph form is shown only when two different
 * records print it, so one `{{ entry.notes }}` cannot excuse the delivery's.
 */
export function readersOf(html, field) {
  const readers = new Set();
  for (const m of html.matchAll(new RegExp(String.raw`([\w.!?]*)\.${field}\b(?!\s*=[^=])`, 'g'))) {
    if (!/Form\b|Form[.!?]/.test(m[1])) readers.add(m[1].replace(/[!?]/g, ''));
  }
  return readers;
}

function templates() {
  return readdirSync(maternityDir)
    .filter((f) => f.endsWith('.html'))
    .map((f) => ({ file: f, html: readFileSync(join(maternityDir, f), 'utf8') }));
}

/** `<form>.<field>` keys bound in a maternity form and shown on no maternity screen. */
export function unshown(all) {
  const everything = all.map((t) => t.html).join('\n');
  const formsByField = new Map();
  for (const { html } of all) {
    for (const { form, field } of formFields(html)) {
      if (!FREE_TEXT_FIELD.test(field)) continue;
      if (!formsByField.has(field)) formsByField.set(field, new Set());
      formsByField.get(field).add(form);
    }
  }
  const missing = [];
  for (const [field, forms] of formsByField) {
    if (readersOf(everything, field).size < forms.size) {
      for (const form of forms) missing.push(`${form}.${field}`);
    }
  }
  return missing.sort();
}

test('QA D1068: every free-text field a maternity form saves is shown again', () => {
  const missing = unshown(templates());
  const excusedButShown = [...NOT_READ_BACK.keys()].filter((k) => !missing.includes(k));
  assert.deepEqual(
    missing.filter((k) => !NOT_READ_BACK.has(k)),
    [],
    'saved and never shown (show it, or list it in NOT_READ_BACK with why)',
  );
  assert.deepEqual(excusedButShown, [], 'stale NOT_READ_BACK entry (now shown or gone): delete it');
});

test('QA D1068 guard: the scan sees the shape the defect had', () => {
  const form = '<textarea [(ngModel)]="observationForm.psychosocialNotes"></textarea>';
  assert.deepEqual(unshown([{ file: 'a', html: form }]), ['observationForm.psychosocialNotes']);
  assert.deepEqual(unshown([{ file: 'a', html: form + '<td>{{ o.psychosocialNotes }}</td>' }]), []);
  // A second form binding the same field is not a read.
  assert.deepEqual(
    unshown([{ file: 'a', html: form + '<input [(ngModel)]="editForm.psychosocialNotes">' }]),
    ['editForm.psychosocialNotes', 'observationForm.psychosocialNotes'],
  );
  // A numeric or coded field is out of scope.
  assert.deepEqual(unshown([{ file: 'a', html: '<input [(ngModel)]="entryForm.pulseBpm">' }]), []);
});

test('QA D1068 guard: one record printing `notes` does not excuse a second form saving `notes`', () => {
  const forms =
    '<textarea [(ngModel)]="deliveryForm.notes"></textarea><textarea [(ngModel)]="entryForm.notes"></textarea>';
  assert.deepEqual(unshown([{ file: 'a', html: forms + '<td>{{ entry.notes }}</td>' }]), [
    'deliveryForm.notes',
    'entryForm.notes',
  ]);
  assert.deepEqual(
    unshown([{ file: 'a', html: forms + '{{ entry.notes }} {{ record.notes }}' }]),
    [],
  );
});
