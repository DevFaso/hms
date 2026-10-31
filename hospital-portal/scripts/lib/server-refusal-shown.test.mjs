// QA D1111 guard (round 19): a write refused by the server shown as a generic
// toast. The second discharge request was refused with a reason ("a request is
// already awaiting the doctor's decision"), and the nurse read only "Échec de
// la soumission de la demande": the handler was `error: () => toast(generic)`.
//
// On the order, encounter, admission and discharge screens, an error handler
// that shows a toast for a WRITE must pass the server's reason first
// (`refusalReason(err) ?? generic`, src/app/shared/refusal-reason.ts). A
// handler that shows only a translated generic text (with or without the error
// parameter) is allowed only for a read (a key naming a load, a missing record or an
// empty result).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const appDir = join(here, '..', '..', 'src', 'app');

/** The screens of the orders/encounters/admissions/discharge flows. */
export const SCREENS = [
  'admissions/admissions.ts',
  'consultations/consultations.ts',
  'discharge/discharge.ts',
  'encounters/encounters.ts',
  'imaging/imaging.ts',
  'maternity/ultrasound-tab.ts',
  'patient-tracker/patient-tracker.component.ts',
  'procedure-orders/procedure-orders.ts',
  'referrals/referrals.ts',
  'transfusion/transfusion.ts',
];

/** A read's generic message: a load, a missing record, an empty answer. */
const READ_KEY = /LOAD|NOT_FOUND|NO_|_NONE|TEMPLATE/;

/**
 * An error handler (with or without the error parameter) whose toast shows a
 * translated generic text and nothing from the server:
 * `error: (…) => …toast.error(this.translate.instant('KEY'))`.
 */
const BLIND_HANDLER =
  /error:\s*\([^)]*\)\s*=>\s*\{?[^}]{0,300}?this\.toast\.error\(\s*this\.translate\.instant\('([A-Z_.]+)'/g;

export function blindWriteHandlers(source) {
  const out = [];
  for (const m of source.matchAll(BLIND_HANDLER)) {
    if (!READ_KEY.test(m[1])) {
      out.push({ key: m[1], line: source.slice(0, m.index).split('\n').length });
    }
  }
  return out;
}

test('QA D1111: a refused write on these screens shows the server reason', () => {
  const problems = [];
  for (const rel of SCREENS) {
    const source = readFileSync(join(appDir, rel), 'utf8');
    for (const h of blindWriteHandlers(source)) {
      problems.push(
        `${rel}:${h.line}: a write's error handler shows only '${h.key}' ` +
          "(take the error and show refusalReason(err) ?? this.translate.instant('…'))",
      );
    }
  }
  assert.deepEqual(problems, [], problems.join('\n'));
});

test('QA D1111 guard: the scan sees the round-19 shape', () => {
  const before = `      error: () => {\n        this.toast.error(this.translate.instant('DISCHARGE.REQUEST_ERROR'));\n      },`;
  assert.deepEqual(
    blindWriteHandlers(before).map((h) => h.key),
    ['DISCHARGE.REQUEST_ERROR'],
  );
  const read = `      error: () => {\n        this.toast.error(this.translate.instant('DISCHARGE.LOAD_APPROVALS_ERROR'));\n      },`;
  assert.deepEqual(blindWriteHandlers(read), []);
  const fixed = `      error: (err: unknown) => {\n        this.toast.error(refusalReason(err) ?? this.translate.instant('DISCHARGE.REQUEST_ERROR'));\n      },`;
  assert.deepEqual(blindWriteHandlers(fixed), []);
  // Taking the error and still showing only the generic text is the same defect.
  const ignored = `      error: (err: unknown) => {\n        this.toast.error(this.translate.instant('DISCHARGE.REQUEST_ERROR'));\n      },`;
  assert.deepEqual(
    blindWriteHandlers(ignored).map((h) => h.key),
    ['DISCHARGE.REQUEST_ERROR'],
  );
});
