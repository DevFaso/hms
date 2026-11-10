// QA D1563 / D1555 guard (round 43): an allergy recorded after the order was
// signed is read again by the server at every gate (the eMAR dose, the
// pharmacist's verification, the fill and the hand-over, the routing to an
// outside pharmacy), which refuses a drug the patient's current allergies stop
// until the clinician acknowledges that allergy with a reason
// (`allergyAcknowledgementReason`, with the card's token).
//
// Rule: every component that calls one of those gates (its `.ts` calls the
// service method) reads the refusal with `allergyAcknowledgementRefusal(` and
// renders `<app-allergy-acknowledgement-dialog>`, so the card is shown and the
// reason asked for instead of a dead-end toast. Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const GATES =
  /\.(administerMedication|createDispense|markReady|handOver|pharmacistVerify|dispatchSms|routeToPartner)\(/;
const READS_REFUSAL = /\ballergyAcknowledgementRefusal\(/;
const DIALOG = /<app-allergy-acknowledgement-dialog\b/;

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

test('QA D1563 / D1555: every screen that gives, verifies, fills or routes an order asks for the allergy acknowledgement', () => {
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
    'read the refusal with allergyAcknowledgementRefusal(err) and render <app-allergy-acknowledgement-dialog>',
  );
});
