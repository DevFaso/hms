// QA round 29: the behavioural form spec (src/app/testing/form-behaviour.spec.ts)
// drives every covered component with a form. The registry it imports is
// generated from the code; a new form that is not in it fails here, so it is
// driven without anyone listing it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { REGISTRY_FILE, formComponents, registrySource } from './form-behaviour-registry.mjs';

/** The `name file` entries of a registry source, whatever its formatting. */
const entries = (s) =>
  [...s.matchAll(/name:\s*'(\w+)',\s*file:\s*'([^']+)'/g)].map((m) => `${m[1]} ${m[2]}`);

test('the form-behaviour registry lists every covered component with a form', () => {
  assert.deepEqual(
    entries(readFileSync(REGISTRY_FILE, 'utf8')),
    entries(registrySource()),
    'run: node scripts/lib/form-behaviour-registry.mjs --write && npx prettier --write src/app/testing/form-behaviour.registry.ts',
  );
});

test('the registry finds the reported round-29 screens', () => {
  const files = formComponents().map((c) => c.file);
  for (const f of [
    'billing/billing',
    'pharmacy/pharmacy-checkout',
    'patient-portal/my-billing/my-billing.component',
    'reception/checkin-dialog/checkin-dialog.component',
    'reception/payment-pending-panel/payment-pending-panel.component',
    'reception/waitlist-panel/waitlist-panel.component',
  ]) {
    assert.ok(files.includes(f), f);
  }
});
