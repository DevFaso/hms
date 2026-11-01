// QA D1156 / D1124 / D1089 guard (round 21): a server refusal shown raw.
//
// Imaging "Créer une Demande" answered an empty form with the bare
// bean-validation text "ne doit pas être nul ne doit pas être vide": the
// handler read `err?.error?.message` itself. That bypasses the shared readers
// (shared/refusal-reason.ts): `refusalReason(err)` puts a validation
// refusal's field reasons first and never shows a 5xx body;
// `labelledRefusalReason(err, translate, labels)` names each field by its
// label, and `refusedFieldIds(err, ids)` lets the page mark the fields.
//
// Rule: no component reads `err.error.message` / `error.error.message`
// directly. Round 21 converted every site in the clinical-forms folders
// (maternity, nurse station, directives, coverage, imaging, discharge,
// reception, pharmacy, lab outbox/results, appointments, profile, tracker,
// encounters). The files below still do it, each pinned with its count; the
// count only goes down (a fixed site fails until its pin is lowered).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const RAW = /\berr(?:or)?\??\.error\??\.message\b/g;

/** Pre-existing sites outside the round-21 folders: QA D1156 class, shrinks only. */
const PINS = new Map([
  ['admin-assignments/admin-assignments.ts', 6],
  ['admin-governance/matrix-tab.ts', 1],
  ['admin-governance/policies-tab.ts', 2],
  ['admin-governance/security-tab.ts', 3],
  ['admin-governance/users-tab.ts', 2],
  ['admissions/admissions.ts', 2],
  ['announcements/announcement-list.ts', 1],
  ['bed-board/bed-board.ts', 1],
  ['billing/billing.ts', 11],
  ['break-glass-review/break-glass-review.ts', 1],
  ['chat/chat.ts', 2],
  ['dashboard/dashboard.ts', 1],
  ['departments/department-list.ts', 1],
  ['digital-signatures/digital-signatures.ts', 2],
  ['feature-flags/feature-flags.ts', 1],
  ['hospitals/hospital-list.ts', 1],
  ['lab/lab-approval-queue/lab-approval-queue.ts', 1],
  ['lab/lab-staff-list/lab-staff-list.ts', 1],
  ['lab/lab-test-config/lab-test-config.ts', 4],
  ['lab/lab.ts', 3],
  ['login/login.ts', 2],
  ['mfa/mfa-challenge.ts', 1],
  ['mfa/mfa-enroll.ts', 1],
  ['micro/microbiology.ts', 8],
  ['on-call/on-call.ts', 3],
  ['onboarding/role-welcome/role-welcome.ts', 1],
  ['organizations/organization-detail.ts', 1],
  ['organizations/organization-list.ts', 2],
  ['panel/panel.ts', 2],
  ['patient-portal/my-appointments/my-appointments.component.ts', 5],
  ['patient-portal/my-appointments/pre-checkin-form/pre-checkin-form.component.ts', 1],
  ['patient-portal/my-billing/my-billing.component.ts', 1],
  ['patient-portal/my-medications/my-medications.component.ts', 1],
  ['patients/break-glass-banner/break-glass-banner.component.ts', 2],
  ['patients/fluid-balance/fluid-balance-tab.component.ts', 2],
  ['patients/growth-chart/growth-chart-tab.component.ts', 1],
  ['patients/micro/micro-tab.component.ts', 1],
  ['patients/patient-demographics-edit/patient-demographics-edit.component.ts', 1],
  ['patients/patient-detail.ts', 2],
  ['patients/patient-form.ts', 3],
  ['patients/patient-photo/patient-photo.component.ts', 2],
  ['prescriptions/prescriptions.ts', 1],
  ['reports/reports.ts', 2],
  ['reset-password/reset-password.ts', 1],
  ['roi/roi.ts', 2],
  ['roles/role-list.ts', 2],
  ['scheduling/scheduling.ts', 1],
  ['slot-admin/slot-admin.ts', 10],
  ['staff/staff-list.ts', 1],
  ['users/user-list.ts', 3],
  ['webhooks/webhooks.ts', 2],
]);

export function rawServerMessages(source) {
  return [...source.matchAll(RAW)].length;
}

test('QA D1156: no component shows a server refusal raw (refusalReason / labelledRefusalReason)', () => {
  const found = new Map();
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const n = rawServerMessages(readFileSync(file, 'utf8'));
    if (n > 0) found.set(relative(appDir, file).replaceAll('\\', '/'), n);
  }
  const problems = [];
  for (const [rel, n] of found) {
    const pinned = PINS.get(rel) ?? 0;
    if (n > pinned) problems.push(`${rel}: ${n} raw err.error.message read(s), ${pinned} pinned`);
  }
  for (const [rel, pinned] of PINS) {
    const n = found.get(rel) ?? 0;
    if (n < pinned) problems.push(`${rel}: pinned ${pinned}, found ${n} — lower the pin`);
  }
  assert.deepEqual(problems, []);
});

test('QA D1156 guard: the scan sees the imaging shape and accepts the shared reader', () => {
  assert.equal(rawServerMessages('const reason = err?.error?.message ?? null;'), 1);
  assert.equal(rawServerMessages('this.toast.error(error.error.message);'), 1);
  assert.equal(rawServerMessages("refusalReason(err) ?? this.translate.instant('X')"), 0);
});
