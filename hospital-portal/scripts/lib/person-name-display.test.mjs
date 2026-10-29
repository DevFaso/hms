// QA D1036 / D926 (class guard): a patient's name is shown whole. Templates
// wrote `{{ p.firstName }} {{ p.lastName }}`, so a middle name captured at
// registration appeared nowhere (chart header, "Nom complet", every picker).
// Every template must render a person through `| personName` instead; the
// staff-account screens below show a user account, which has no middle name.
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { loadTemplates } from './template-gate.mjs';

const STAFF_ACCOUNT_SCREENS = new Map([
  ['src/app/admin-governance/users-tab.html', 'a user account (no middle name on User)'],
  ['src/app/staff/staff-list.html', 'a user account (no middle name on User)'],
  ['src/app/super-admin/emergency/emergency.html', 'a user account (no middle name on User)'],
  ['src/app/users/user-list.html', 'a user account (no middle name on User)'],
  // D1036 class, not fixed here: the lines are the ones fix/r17-lab-pharmacy-billing
  // rewrites for D1052 ("Patient: …" colons); that branch switches them to
  // `| personName` and deletes this entry (flagged in the hand-back).
  ['src/app/pharmacy/dispensing.html', 'D1036 sibling owned by fix/r17-lab-pharmacy-billing (D1052 lines)'],
]);

const FIRST_LAST = /\{\{\s*([\w.?!]+)\.firstName\s*\}\}\s*\{\{\s*\1\.lastName\s*\}\}/g;

export function firstLastSites(html) {
  return [...html.matchAll(FIRST_LAST)].map((m) => m[1]);
}

test('QA D1036: every patient name is rendered whole, through | personName', () => {
  const offenders = [];
  const allowedSeen = new Set();
  for (const { rel, text } of loadTemplates()) {
    const sites = firstLastSites(text);
    if (sites.length === 0) continue;
    if (STAFF_ACCOUNT_SCREENS.has(rel)) {
      allowedSeen.add(rel);
      continue;
    }
    offenders.push(`${rel}: ${sites.join(', ')}`);
  }
  assert.deepEqual(offenders, []);
  // An allowance whose site is gone is deleted, so the list cannot outlive what it excused.
  assert.deepEqual(
    [...STAFF_ACCOUNT_SCREENS.keys()].filter((f) => !allowedSeen.has(f)),
    [],
  );
});

test('the scan sees the first-and-last shape', () => {
  assert.deepEqual(firstLastSites('<b>{{ p.firstName }} {{ p.lastName }}</b>'), ['p']);
  assert.deepEqual(firstLastSites('<b>{{ p | personName }}</b>'), []);
});
