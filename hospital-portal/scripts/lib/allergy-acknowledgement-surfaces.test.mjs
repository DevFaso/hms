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

// QA D1584 / D1585: a gate's allergy card says where the conflict stands (awaiting review, the
// prescriber's CONTINUE with its plan, or STOP), from the review state the server reads on the gate's
// own token. Every card list bound to a gate response's allergy cards renders
// `<app-allergy-review-state>` right beside it, and so does the nurse-station row's allergy badge.
const GATE_CARD_LIST =
  /<app-cds-card-list\s+\[cards\]="[^"]*(?:allergyAlerts|allergyAdvisories|promptAdvisories)[^"]*"/g;
const ROW_BADGE = /\.cdsAdvisories!\[0\]\.summary/g;
const REVIEW_STATE = /<app-allergy-review-state\b/;
/** How far after a card list (or a row badge) its review state may sit. */
const NEAR = 400;

export function gateCardsWithoutReviewState() {
  const missing = [];
  const seen = [];
  for (const file of walk(appDir, ['.html', '.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const source = readFileSync(file, 'utf8');
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const pattern of [GATE_CARD_LIST, ROW_BADGE]) {
      for (const match of source.matchAll(pattern)) {
        seen.push(rel);
        const after = source.slice(match.index, match.index + match[0].length + NEAR);
        if (!REVIEW_STATE.test(after)) {
          const line = source.slice(0, match.index).split('\n').length;
          missing.push(`${rel}:${line}`);
        }
      }
    }
  }
  return { seen, missing };
}

test('QA D1584: every gate allergy card shows the prescriber review state beside it', () => {
  const { seen, missing } = gateCardsWithoutReviewState();
  for (const expected of [
    'nurse-station/emar/emar.component.html',
    'nurse-station/nurse-station.html',
    'pharmacy/dispensing.html',
  ]) {
    assert.ok(seen.includes(expected), `the scan must see ${expected}; saw ${seen.join(', ')}`);
  }
  assert.deepEqual(
    missing,
    [],
    'render <app-allergy-review-state [review]="…allergyReview"> beside the gate allergy cards',
  );
});
