import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { navEntries, unreachableNamedRoles } from './nav-nested-roles.mjs';

const shell = fileURLToPath(new URL('../../src/app/shell/shell.ts', import.meta.url));

// QA D2927 (r66): what the shell looked like. The deferrals entry names finance; the gate around it does not.
const NESTED = `
  build() {
    if (this.hasAnyRole(['ROLE_PHARMACIST', 'ROLE_HOSPITAL_ADMIN'])) {
      items.push(
        { translationKey: 'NAV.STOCK', route: '/pharmacy/inventory' },
        {
          translationKey: 'NAV.DEFERRALS',
          route: '/pharmacy/billing-deferrals',
          roles: ['ROLE_PHARMACIST', 'ROLE_BILLING_SPECIALIST', 'ROLE_ACCOUNTANT', 'ROLE_HOSPITAL_ADMIN'],
        },
      );
    }
  }`;

test('D2927: a role an entry names but its gate never lets in is found', () => {
  assert.deepEqual(unreachableNamedRoles(NESTED), [
    '/pharmacy/billing-deferrals ← ROLE_ACCOUNTANT',
    '/pharmacy/billing-deferrals ← ROLE_BILLING_SPECIALIST',
  ]);
});

test('D2927: an entry behind a gate of its own, or pushed again for the role, reaches it', () => {
  const ownGate = `
    if (this.hasAnyRole(['ROLE_PHARMACIST', 'ROLE_ACCOUNTANT'])) {
      items.push({ translationKey: 'NAV.DEFERRALS', route: '/pharmacy/billing-deferrals' });
    }`;
  assert.deepEqual(unreachableNamedRoles(ownGate), []);
  const pushedAgain = `
    if (this.hasAnyRole(['ROLE_PHARMACIST'])) {
      items.push({ translationKey: 'NAV.D', route: '/pharmacy/dispensing', roles: ['ROLE_PHARMACIST', 'ROLE_PHARMACY_VERIFIER'] });
    }
    if (this.hasAnyRole(['ROLE_PHARMACY_VERIFIER'])) {
      items.push({ translationKey: 'NAV.D', route: '/pharmacy/dispensing' });
    }`;
  assert.deepEqual(unreachableNamedRoles(pushedAgain), []);
  // An entry with no gate around it is filtered by its own list alone.
  const ungated = `items.push({ translationKey: 'NAV.X', route: '/x', roles: ['ROLE_ACCOUNTANT'] });`;
  assert.deepEqual(unreachableNamedRoles(ungated), []);
  // Only the then-branch sits behind the gate.
  const elseBranch = `
    if (this.hasAnyRole(['ROLE_PHARMACIST'])) {
      items.push({ translationKey: 'NAV.A', route: '/a' });
    } else {
      items.push({ translationKey: 'NAV.B', route: '/b', roles: ['ROLE_ACCOUNTANT'] });
    }`;
  assert.deepEqual(unreachableNamedRoles(elseBranch), []);
});

test('the shell: every role a nav entry names can see that entry', () => {
  const source = readFileSync(shell, 'utf8');
  assert.ok(navEntries(source).length > 80, 'the scan still reads the nav builder');
  assert.deepEqual(
    unreachableNamedRoles(source),
    [],
    'a nav entry names a role that the gate around it never lets in: the role cannot see the entry ' +
      '(QA D2927). Give the entry a gate of its own, the same list as its route guard.',
  );
});

test('D2927: finance reaches the deferrals page, the reconciliation and the claims from the menu', () => {
  const entries = navEntries(readFileSync(shell, 'utf8'));
  const sees = (route, role) =>
    entries.some(
      (e) =>
        e.route === route &&
        e.gates.every((g) => g.has(role)) &&
        (!e.roles || e.roles.includes(role)),
    );
  for (const role of [
    'ROLE_ACCOUNTANT',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_PHARMACIST',
  ]) {
    assert.ok(sees('/pharmacy/billing-deferrals', role), `${role} sees the deferrals page`);
  }
  assert.ok(sees('/pharmacy/reconciliation', 'ROLE_ACCOUNTANT'));
  assert.ok(sees('/pharmacy/claims', 'ROLE_ACCOUNTANT'));
  assert.ok(!sees('/pharmacy/billing-deferrals', 'ROLE_RECEPTIONIST'), 'never the front desk');
});
