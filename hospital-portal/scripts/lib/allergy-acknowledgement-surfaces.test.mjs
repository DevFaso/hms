// QA D1563 / D1555 guard (round 43), Policy 4 (user recommendation 2026-11-10,
// pending clinical approval): an allergy recorded after the order was signed is
// read again by the server at every gate (the eMAR dose, the pharmacist's
// verification, the fill and the hand-over, the routing to an outside
// pharmacy), which HOLDS a drug the patient's current allergies stop until an
// authorized prescriber reviews the conflict on the order. A nurse's or a
// pharmacist's own reason never releases it.
//
// Rules:
// - every component that calls one of those gates (its `.ts` calls the service
//   method) reads the hold with `allergyHoldRefusal(` and renders
//   `<app-allergy-hold-dialog>`, so the card and who was notified are shown
//   instead of a dead-end toast;
// - no source sends `allergyAcknowledgementReason` (or its token): the staff
//   release is gone;
// - the prescriber's review is reachable from the order detail and from the
//   in-basket notice (`<app-allergy-review-dialog>`).
// Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const GATES =
  /\.(administerMedication|createDispense|markReady|handOver|pharmacistVerify|dispatchSms|routeToPartner)\(/;
const READS_REFUSAL = /\ballergyHoldRefusal\(/;
const DIALOG = /<app-allergy-hold-dialog\b/;
const STAFF_REASON = /allergyAcknowledgement(Reason|Token)/;
const REVIEW_DIALOG = /<app-allergy-review-dialog\b/;

/** The component's template: its templateUrl file, or its inline template. */
function templateOf(file, source) {
  const url = /templateUrl:\s*['"]([^'"]+)['"]/.exec(source)?.[1];
  if (url) {
    const path = join(dirname(file), url);
    return existsSync(path) ? readFileSync(path, 'utf8') : '';
  }
  return source;
}

export function gateSurfacesWithoutAcknowledgement() {
  const missing = [];
  const surfaces = [];
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const source = readFileSync(file, 'utf8');
    if (!GATES.test(source) || !/@Component\(/.test(source)) continue;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    surfaces.push(rel);
    if (!READS_REFUSAL.test(source) || !DIALOG.test(templateOf(file, source))) missing.push(rel);
  }
  return { surfaces, missing };
}

test('QA D1563 / D1555, Policy 4: every screen that gives, verifies, fills or routes an order shows the allergy hold', () => {
  const { surfaces, missing } = gateSurfacesWithoutAcknowledgement();
  for (const expected of [
    'nurse-station/nurse-station.ts',
    'nurse-station/emar/emar.component.ts',
    'pharmacy/dispensing.ts',
    'pharmacy/stock-routing.ts',
    'prescriptions/prescriptions.ts',
  ]) {
    assert.ok(
      surfaces.includes(expected),
      `the scan must see ${expected}; saw ${surfaces.join(', ')}`,
    );
  }
  assert.deepEqual(
    missing,
    [],
    'read the hold with allergyHoldRefusal(err) and render <app-allergy-hold-dialog>',
  );
});

test('Policy 4: no screen sends a staff allergy reason as a release', () => {
  const senders = [];
  for (const file of walk(appDir, ['.ts', '.html'])) {
    if (file.endsWith('.spec.ts')) continue;
    if (STAFF_REASON.test(readFileSync(file, 'utf8'))) {
      senders.push(relative(appDir, file).replaceAll('\\', '/'));
    }
  }
  assert.deepEqual(
    senders,
    [],
    'only the prescriber review releases an allergy conflict (Policy 4)',
  );
});

test('Policy 4: the prescriber review is reachable from the order detail and from its in-basket notice', () => {
  for (const component of [
    'prescriptions/prescriptions.ts',
    'dashboard/in-basket-panel/in-basket-panel.ts',
  ]) {
    const file = join(appDir, component);
    const template = templateOf(file, readFileSync(file, 'utf8'));
    assert.ok(REVIEW_DIALOG.test(template), `${component} renders <app-allergy-review-dialog>`);
  }
});
