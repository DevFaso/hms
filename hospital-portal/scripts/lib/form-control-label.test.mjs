// QA D993 guard: every text field, text area and select in the portal has an
// accessible name beyond its placeholder (see form-control-label.mjs).
//
// The known offenders below are files other round-16 branches own; each is
// pinned at its current count (a ratchet: a new unnamed control fails, and a
// fixed one must lower its count here). Never add a file to this list.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';
import { unlabelledControls } from './form-control-label.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/** D993 (round 16): unnamed controls left in files this branch does not own. */
const KNOWN = {
  'admin-assignments/admin-assignments.html': 3,
  'admin-governance/matrix-tab.html': 5,
  'admin-governance/users-tab.html': 1,
  'chat/chat.html': 1,
  'consultations/consultations.html': 1,
  'departments/department-list.html': 2,
  'digital-signatures/digital-signatures.html': 3,
  'discharge/discharge.html': 7,
  'lock-screen/lock-screen.html': 1,
  'mfa/mfa-challenge.html': 1,
  'mfa/mfa-enroll.html': 1,
  'patient-portal/my-medical-history/my-medical-history.component.html': 4,
  'profile/profile.html': 1,
};

function offendersByFile() {
  const counts = {};
  for (const file of walk(appDir, ['.html'])) {
    const rel = relative(appDir, file).split('\\').join('/');
    const found = unlabelledControls(readFileSync(file, 'utf8'), rel);
    if (found.length) counts[rel] = found;
  }
  return counts;
}

test('QA D993: no form control is named by its placeholder alone', () => {
  const found = offendersByFile();
  const problems = [];
  for (const [file, list] of Object.entries(found)) {
    const allowed = KNOWN[file] ?? 0;
    if (list.length > allowed) problems.push(...list);
  }
  assert.deepEqual(problems, []);
});

test('QA D993: a fixed file lowers its pinned count', () => {
  const found = offendersByFile();
  const stale = Object.entries(KNOWN)
    .filter(([file, n]) => (found[file]?.length ?? 0) < n)
    .map(([file, n]) => `${file}: pinned ${n}, found ${found[file]?.length ?? 0}`);
  assert.deepEqual(stale, []);
});

test('QA D993 guard: the scan sees the shape the defect had', () => {
  const marReason =
    '<textarea class="reason-input" [placeholder]="\'NURSE.ENTER_REASON\' | translate"></textarea>';
  assert.equal(unlabelledControls(marReason).length, 1);
  const named = '<label for="r">x</label><textarea id="r"></textarea>';
  assert.equal(unlabelledControls(named).length, 0);
  const aria = '<input data-testid="emar-dose" [attr.aria-label]="\'NURSE.DOSE\' | translate" />';
  assert.equal(unlabelledControls(aria).length, 0);
  const wrapped = '<label><input type="checkbox" /> Tick</label>';
  assert.equal(unlabelledControls(wrapped).length, 0);
  const bound = '<label [for]="\'min-\' + i">Min</label><input [id]="\'min-\' + i" />';
  assert.equal(unlabelledControls(bound).length, 0);
});
