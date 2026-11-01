// QA D1157 / D1160 / D1159 guard (round 21): an action that ends a record
// sent with nothing in it.
//
// "Fermer" on a waitlist entry and "Annuler" on a recall posted `{}` on one
// click: no confirmation, no reason, and the server accepted it. Every
// service call to an endpoint that cancels, closes, voids, revokes,
// discontinues, withdraws or rejects must carry what the user said (a
// reason); an empty body `{}` fails here unless the site is pinned with why
// it needs none. The server refuses a blank reason too (CancelVoidReasonGuardTest
// on the backend); this keeps the portal from offering a one-click path to it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/**
 * `http.post<T>(\`…/cancel\`, {})`, across line breaks. An empty body
 * followed by options (`{ params }` carrying the reason) is not empty.
 */
const EMPTY_TERMINAL_CALL =
  /http\.(?:post|put|patch)(?:<[^>]*>)?\(\s*`([^`]*\/(?:cancel|close|void|revoke|discontinue|withdraw|reject)\b[^`]*)`\s*,\s*\{\s*\}\s*,?\s*\)/g;

/** Sites that end a record legitimately without a reason, each with why. */
const PINS = new Map([
  [
    'reception/reception.service.ts::/recalls/${id}/close',
    'closing a recall records that it was FULFILLED (the patient came back); "Annuler" is the cancel and takes a reason (QA D1157)',
  ],
  [
    'services/advance-directive.service.ts::/advance-directives/${id}/revoke',
    'QA D1148 class, left for the backend wave: the revoke confirmation asks no reason yet; the server stamps who and when',
  ],
  [
    'services/hospital-lifecycle.service.ts::${BASE}/${encodeURIComponent(hospitalId)}/cancel-purge',
    'a platform administrator stopping a scheduled purge (it keeps the record), not ending one',
  ],
  [
    'services/integration-keys.service.ts::/api-keys/${id}/revoke',
    'an integration credential revoked by an administrator, not a clinical record',
  ],
  [
    'services/integration-keys.service.ts::/webhook-endpoints/${id}/revoke',
    'an integration endpoint revoked by an administrator, not a clinical record',
  ],
  [
    'services/staff-scheduling.service.ts::/staff/scheduling/leaves/${leaveId}/cancel',
    'a staff member withdrawing their own leave request, not a clinical record',
  ],
]);

export function emptyTerminalCalls(source) {
  return [...source.matchAll(EMPTY_TERMINAL_CALL)].map((m) => m[1]);
}

test('QA D1157: no portal call ends a record with an empty body', () => {
  const found = new Set();
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const url of emptyTerminalCalls(readFileSync(file, 'utf8'))) found.add(`${rel}::${url}`);
  }
  const unpinned = [...found].filter((site) => !PINS.has(site));
  assert.deepEqual(
    unpinned,
    [],
    'send the reason the user gave (a reason dialog), or pin the site with why',
  );
  const stale = [...PINS.keys()].filter((site) => !found.has(site));
  assert.deepEqual(stale, [], 'a pinned site is gone: delete its pin');
});

test('QA D1157 guard: the scan sees the waitlist shape and accepts a reason', () => {
  const broken = 'return this.http.post<void>(`${this.base}/waitlist/${id}/close`, {});';
  assert.deepEqual(emptyTerminalCalls(broken), ['${this.base}/waitlist/${id}/close']);
  const wrapped =
    'return this.http.post<RecallResponse>(\n      `/recalls/${id}/cancel`,\n      {},\n    );';
  assert.equal(emptyTerminalCalls(wrapped).length, 1);
  const fixed =
    'return this.http.post<void>(`${this.base}/waitlist/${id}/close`, { reason }, { params });';
  assert.deepEqual(emptyTerminalCalls(fixed), []);
  const inParams = 'return this.http.post<T>(`/consents/${id}/revoke`, {}, { params });';
  assert.deepEqual(emptyTerminalCalls(inParams), []);
});
