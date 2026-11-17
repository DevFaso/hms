// QA CX-006 guard (round 33): the super-admin dashboard joins the fields of a
// recent record into one sentence in TypeScript, where `i18n:raw-enums` (it reads
// templates) cannot see them: ROUTINE, URGENT, REQUESTED and CANCELLED reached the
// screen verbatim in FR and ES. Every coded field of ACTIVITY_FIELDS has its label
// domain in ACTIVITY_ENUM_DOMAINS, and every free-text field is named here with why.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const file = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'src/app/super-admin/super-admin.ts',
);
const source = readFileSync(file, 'utf8').replaceAll('\r\n', '\n');

/** Typed by a person, or a name: rendered as typed. */
const FREE_TEXT = new Set([
  'patientName',
  'patientFullName',
  'specialtyRequested',
  'chiefComplaint',
  'medicationName',
  'dosage',
  'frequency',
  'labTestName',
  'resultValue',
  'name',
  'labTestCode',
  'category',
  'receivingHospitalName',
]);

function block(name) {
  const start = source.indexOf(`ACTIVITY_${name}:`);
  assert.ok(start >= 0, name);
  const open = source.indexOf('= {', start);
  return source.slice(open, source.indexOf('\n  };', open));
}

test('QA CX-006: every field of a recent-activity summary is a label or named as free text', () => {
  const domains = new Map();
  for (const m of block('ENUM_DOMAINS').matchAll(/^ {4}(\w+): \{(.*)\},?$/gm)) {
    domains.set(m[1], new Set([...m[2].matchAll(/(\w+):/g)].map((f) => f[1])));
  }
  const raw = [];
  let tabs = 0;
  for (const m of block('FIELDS').matchAll(/^ {4}(\w+): \[(.*)\],?$/gm)) {
    tabs++;
    for (const f of m[2].matchAll(/'(\w+)'/g)) {
      if (!domains.get(m[1])?.has(f[1]) && !FREE_TEXT.has(f[1])) raw.push(`${m[1]}.${f[1]}`);
    }
  }
  assert.ok(tabs >= 8, 'the activity tabs were read');
  assert.deepEqual(
    raw,
    [],
    'a coded field goes through ACTIVITY_ENUM_DOMAINS, or is named free text',
  );
});
