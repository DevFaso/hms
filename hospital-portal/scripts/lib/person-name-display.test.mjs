// QA D1036 / D926 (class guard): a patient's name is shown whole. Templates
// wrote `{{ p.firstName }} {{ p.lastName }}`, so a middle name captured at
// registration appeared nowhere (chart header, "Nom complet", every picker).
// Every template must render a person through `| personName` instead; the
// staff-account screens below show a user account, which has no middle name.
//
// Round 18 (D1036 NOT_FIXED for the third round): the first scan only saw a
// receiver made of `[\w.?!]`, so `{{ selectedPatient()!.firstName }}` (the
// appointment-form chip and eight other pickers) and every `.ts` join
// (`${p.firstName} ${p.lastName}`, `p.firstName + ' ' + p.lastName`,
// `[p.firstName, p.lastName].join(' ')`) slipped through. Both halves are
// scanned now, with any receiver (calls, `!`, `?.`).
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { loadTemplates } from './template-gate.mjs';
import { componentSources } from './component-sources.mjs';

/** Templates that show a USER account (no middle name on User), not a patient. */
const STAFF_ACCOUNT_SCREENS = new Map([
  ['src/app/admin-governance/users-tab.html', 'a user account (no middle name on User)'],
  ['src/app/staff/staff-list.html', 'a user account (no middle name on User)'],
  ['src/app/super-admin/emergency/emergency.html', 'a user account (no middle name on User)'],
  ['src/app/users/user-list.html', 'a user account (no middle name on User)'],
]);

/** `.ts` sites that join a first and last name but do not display a patient. */
const TS_NOT_A_PATIENT = new Map([
  ['src/app/admin/admin.ts', 'the signed-in admin profile (a User, no middle name)'],
  ['src/app/chat/chat.ts', 'search fields of a staff ChatRecipient (a User, no middle name)'],
  [
    'src/app/admissions/admissions.ts',
    'the signed-in user profile, for the attending default (a User)',
  ],
  ['src/app/lock-screen/lock-screen.ts', 'the signed-in user profile (a User)'],
  [
    'src/app/procedure-orders/procedure-orders.ts',
    'the signed-in user, as the consent witness (a User)',
  ],
  ['src/app/profile/profile.ts', 'the signed-in user profile (a User)'],
  ['src/app/provider/provider-staff/provider-staff.ts', 'a provider staff member (a User)'],
  ['src/app/shell/shell.ts', 'the signed-in user, in the top bar (a User)'],
  ['src/app/users/user-list.ts', 'a user account export row (no middle name on User)'],
  [
    'src/app/patients/patient-form.ts',
    'the duplicate-candidate QUERY sent while typing (a match, not a display) and the username seed',
  ],
]);

// A receiver: identifiers, dots, calls, `!` and `?` (optional chaining), and
// index access — anything a template or a `.ts` expression names a record by.
const RECV = String.raw`[\w$.?!()\[\]]+?`;

/** An optional `?? ''` fallback inside an interpolation. */
const OR_EMPTY = String.raw`(?:\s*\?\?\s*(?:''|""))?`;

const FIRST_LAST = new RegExp(
  String.raw`\{\{\s*(${RECV})\??\.firstName\s*\}\}\s*\{\{\s*\1\??\.lastName\s*\}\}`,
  'g',
);

const TS_SHAPES = [
  // `${p.firstName} ${p.lastName}` (also with a `?? ''` fallback in each)
  new RegExp(
    String.raw`\$\{\s*(${RECV})\??\.firstName${OR_EMPTY}\s*\}\s*\$\{\s*\1\??\.lastName${OR_EMPTY}\s*\}`,
    'g',
  ),
  // p.firstName + ' ' + p.lastName   (any spacing, either quote)
  new RegExp(
    String.raw`\b(${RECV})\??\.firstName\s*\+\s*(['"\x60])\s*\2\s*\+\s*\1\??\.lastName\b`,
    'g',
  ),
  // [p.firstName, p.lastName] (then .filter / .join)
  new RegExp(String.raw`\[\s*(${RECV})\??\.firstName\s*,\s*\1\??\.lastName\s*\]`, 'g'),
];

export function firstLastSites(html) {
  return [...html.matchAll(FIRST_LAST)].map((m) => m[1]);
}

export function tsFirstLastSites(source) {
  const out = [];
  for (const re of TS_SHAPES) for (const m of source.matchAll(re)) out.push(m[1]);
  return out;
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

test('QA D1036: no .ts code joins a first and last name by hand (use personName())', () => {
  const offenders = [];
  const allowedSeen = new Set();
  for (const { file, text } of componentSources()) {
    const sites = tsFirstLastSites(text);
    if (sites.length === 0) continue;
    if (TS_NOT_A_PATIENT.has(file)) {
      allowedSeen.add(file);
      continue;
    }
    offenders.push(`${file}: ${sites.join(', ')}`);
  }
  assert.deepEqual(offenders, []);
  assert.deepEqual(
    [...TS_NOT_A_PATIENT.keys()].filter((f) => !allowedSeen.has(f)),
    [],
  );
});

test('the scan sees the first-and-last shape, with any receiver', () => {
  assert.deepEqual(firstLastSites('<b>{{ p.firstName }} {{ p.lastName }}</b>'), ['p']);
  assert.deepEqual(
    firstLastSites(
      '<span>{{ selectedPatient()!.firstName }} {{ selectedPatient()!.lastName }}</span>',
    ),
    ['selectedPatient()!'],
  );
  assert.deepEqual(firstLastSites('{{ formPatient()?.firstName }} {{ formPatient()?.lastName }}'), [
    'formPatient()',
  ]);
  assert.deepEqual(firstLastSites('<b>{{ p | personName }}</b>'), []);
});

test('the .ts scan sees template literals, concatenation and array joins', () => {
  assert.deepEqual(tsFirstLastSites('x = `${p.firstName} ${p.lastName}`;'), ['p']);
  assert.deepEqual(tsFirstLastSites("q.set(p.firstName + ' ' + p.lastName);"), ['p']);
  assert.deepEqual(tsFirstLastSites('[rx.patient?.firstName, rx.patient?.lastName].join(" ")'), [
    'rx.patient',
  ]);
  assert.deepEqual(tsFirstLastSites("`${u.firstName ?? ''} ${u.lastName ?? ''}`"), ['u']);
  assert.deepEqual(tsFirstLastSites('personName(p)'), []);
  // Initials are not a full name.
  assert.deepEqual(tsFirstLastSites('p.firstName.charAt(0) + p.lastName.charAt(0)'), []);
});
