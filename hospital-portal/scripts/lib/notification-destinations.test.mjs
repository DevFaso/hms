// QA D1110 (D983 class) guard: every notification type the server writes has
// a decided destination.
//
// QA D983 gave the patient's notifications a destination (a chat message opens
// the conversation). The lab-result notification was not in the table, so
// clicking "Nouveau résultat d'analyse" stayed on /my-notifications. The table
// covered the types somebody remembered, not the types the server writes.
//
// This reads every type hospital-core writes (the type argument of each
// createNotification / createLiteralNotification call, a literal or a
// constant of the same file) and requires each one to be either a key of the
// patient portal's DESTINATIONS table or declared below as having no page,
// with its reason. A new type is therefore a test failure until somebody
// decides where it leads.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..', '..');
const javaRoot = join(repo, 'hospital-core', 'src', 'main', 'java');
const destinationsFile = join(
  here,
  '..',
  '..',
  'src',
  'app',
  'patient-portal',
  'my-notifications',
  'my-notifications.component.ts',
);

/**
 * Types with no patient-portal page, and why. A type a patient can receive
 * that has a page to open does NOT belong here.
 */
const NO_DESTINATION = {
  ADMIN_MESSAGE: 'a free-text message from an administrator; the text is the whole notice',
  ANNOUNCEMENT: 'a hospital announcement; the text is the whole notice',
  CONSULTATION_UPDATE: 'staff only (consultant and requester); the staff bell expands the message',
  CRITICAL_LAB_RESULT:
    'staff only (ordering clinician); read back from the critical-results worklist',
  CRITICAL_LAB_RESULT_ESCALATION:
    'staff only (escalation chain); the staff bell expands the message',
  CRITICAL_IMAGING_FINDING: 'staff only (ordering clinician); the staff bell expands the message',
  CRITICAL_IMAGING_FINDING_ESCALATION:
    'staff only (escalation chain); the staff bell expands the message',
  LAB_ORDER_RECEIVED: 'staff only (performing laboratory); the staff bell expands the message',
  LAB_SPECIMEN_REJECTED: 'staff only (ordering clinician); the staff bell expands the message',
  LICENSE_EXPIRY: 'staff only (credentialing); the staff bell expands the message',
  MATERNITY_ALERT: 'staff only (maternity team); the staff bell expands the message',
  NEWS2_ESCALATION: 'staff only (ward escalation); the staff bell expands the message',
  OBSTETRIC_ALERT: 'staff only (maternity team); the staff bell expands the message',
  OBSTETRIC_ALERT_ESCALATION: 'staff only (escalation chain); the staff bell expands the message',
  POSITIVE_CULTURE_RESULT: 'staff only (ordering clinician); the staff bell expands the message',
  PRO_CRITICAL_ITEM: 'staff only (care team); the staff bell expands the message',
  PRO_CRITICAL_ITEM_ESCALATION: 'staff only (escalation chain); the staff bell expands the message',
  PRO_SCREEN_POSITIVE: 'staff only (care team); the staff bell expands the message',
  REORDER_ALERT: 'staff only (pharmacy stock); the staff bell expands the message',
};

const CALL = /create(?:Literal)?Notification\s*\(/g;

/** The text of each createNotification(...) call in `source`, parentheses balanced. */
function calls(source) {
  const out = [];
  for (const m of source.matchAll(CALL)) {
    let depth = 0;
    let i = m.index + m[0].length - 1;
    const start = i;
    for (; i < source.length; i++) {
      if (source[i] === '(') depth++;
      else if (source[i] === ')' && --depth === 0) break;
    }
    out.push(source.slice(start, i + 1));
  }
  return out;
}

/** Every notification type written in one Java source: literals and same-file constants. */
export function typesWritten(source) {
  const constants = new Map();
  for (const m of source.matchAll(
    /static\s+final\s+String\s+(\w+)\s*=\s*"([A-Z][A-Z0-9_]+)"\s*;/g,
  )) {
    constants.set(m[1], m[2]);
  }
  const types = new Set();
  for (const call of calls(source)) {
    // The interface's own declarations name parameters, not types.
    if (/String\s+type\b/.test(call)) continue;
    for (const lit of call.matchAll(/"([A-Z][A-Z0-9_]{2,})"/g)) types.add(lit[1]);
    for (const id of call.matchAll(/\b([A-Z][A-Z0-9_]*TYPE[A-Z0-9_]*)\b/g)) {
      if (constants.has(id[1])) types.add(constants.get(id[1]));
    }
  }
  return types;
}

function serverTypes() {
  const all = new Set();
  for (const file of walk(javaRoot, ['.java'])) {
    const source = readFileSync(file, 'utf8');
    if (!CALL.test(source)) continue;
    CALL.lastIndex = 0;
    for (const t of typesWritten(source)) all.add(t);
  }
  return all;
}

function portalDestinations() {
  const source = readFileSync(destinationsFile, 'utf8');
  const table = /DESTINATIONS[\s\S]*?=\s*\{([\s\S]*?)\n\s*\};/.exec(source);
  assert.ok(table, 'DESTINATIONS table not found in my-notifications.component.ts');
  return new Set([...table[1].matchAll(/^\s*([A-Z][A-Z0-9_]+)\s*:/gm)].map((m) => m[1]));
}

test('QA D1110: every notification type the server writes has a destination or a reason for none', () => {
  const written = serverTypes();
  const destinations = portalDestinations();
  assert.ok(
    written.size >= 20,
    `found only ${written.size} notification types — the scan is broken`,
  );
  assert.ok(
    written.has('LAB_RESULT') && written.has('CHAT_MESSAGE'),
    'the scan misses known types',
  );

  const undecided = [...written].filter((t) => !destinations.has(t) && !(t in NO_DESTINATION));
  assert.deepEqual(undecided.sort(), [], 'types with no destination and no stated reason');

  const both = [...destinations].filter((t) => t in NO_DESTINATION);
  assert.deepEqual(both, [], 'a type cannot both open a page and have none');

  const stale = Object.keys(NO_DESTINATION).filter((t) => !written.has(t));
  assert.deepEqual(stale, [], 'NO_DESTINATION entries the server no longer writes (delete them)');
});

test('QA D1110: the lab-result notification opens the patient results page', () => {
  const source = readFileSync(destinationsFile, 'utf8');
  assert.match(source, /LAB_RESULT:\s*\{\s*path:\s*'\/my-lab-results'/);
});

test('QA D1110 guard: the scan reads literal and constant types', () => {
  const sample = `
    private static final String NOTIFICATION_TYPE = "CRITICAL_LAB_RESULT";
    void a() {
      notificationService.createNotification(key, args, user, NOTIFICATION_TYPE, id);
      notificationService.createLiteralNotification(text,
          user, "PRENATAL_REMINDER");
    }`;
  assert.deepEqual([...typesWritten(sample)].sort(), ['CRITICAL_LAB_RESULT', 'PRENATAL_REMINDER']);
});
