// QA D1225 guard (round 24): a MAR row is never read without the reasons the
// administer call required.
//
// The early-dose (QA D1210), hold, refusal and five-rights override reasons
// were stored on the MAR row and shown on no screen: the station row read
// "Dernière dose : Administrée" and the eMAR listed due doses only, so the
// next nurse never learned that a dose was given early, or why.
//
// Rule: every component that reads MAR rows (its `.ts` names the
// `NurseMedicationTask` model) renders `<app-mar-dose-history>` — the dose
// history with each dose's reason, override reason, giver and witness — in
// its template. The model itself (services/nurse-task.service.ts) is not a
// screen. Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const MODEL = /\bNurseMedicationTask\b/;
const HISTORY = /<app-mar-dose-history\b/;

/** The component's template: its templateUrl file, or its inline template. */
function templateOf(file, source) {
  const url = /templateUrl:\s*['"]([^'"]+)['"]/.exec(source)?.[1];
  if (url) {
    const path = join(dirname(file), url);
    return existsSync(path) ? readFileSync(path, 'utf8') : '';
  }
  return source;
}

export function marSurfacesWithoutHistory() {
  const missing = [];
  const surfaces = [];
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const source = readFileSync(file, 'utf8');
    if (!MODEL.test(source) || !/@Component\(/.test(source)) continue;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    surfaces.push(rel);
    if (!HISTORY.test(templateOf(file, source))) missing.push(rel);
  }
  return { surfaces, missing };
}

test('QA D1225: every screen that reads MAR rows shows the dose history and its reasons', () => {
  const { surfaces, missing } = marSurfacesWithoutHistory();
  assert.ok(
    surfaces.includes('nurse-station/nurse-station.ts') &&
      surfaces.includes('nurse-station/emar/emar.component.ts'),
    `the scan must see the station and the eMAR; saw ${surfaces.join(', ')}`,
  );
  assert.deepEqual(
    missing,
    [],
    'render <app-mar-dose-history [doses]="task.doseHistory ?? []"> where the MAR row is read',
  );
});
