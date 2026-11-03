// QA round 30 (user decision 2026-11-02): an as-needed (PRN) order carries the
// prescriber's minimum interval between doses. The server decides what reads
// as as-needed (hospital-core MarDoseSchedule.isAsNeeded, the ONE frequency
// reader there) and refuses a PRN order without its interval; the prescribing
// form shows the interval field from its own copy of the words
// (src/app/shared/as-needed-frequency.ts). If the two drift, the form hides
// the field the server will refuse the order for, or asks for one the server
// refuses (an interval on a fixed schedule). This test reads both files.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const PORTAL_HELPER = join(here, '..', '..', 'src', 'app', 'shared', 'as-needed-frequency.ts');
const CASES = join(here, '..', '..', 'src', 'app', 'shared', 'as-needed-frequency.cases.json');
const SERVER = join(
  here,
  '..',
  '..',
  '..',
  'hospital-core',
  'src',
  'main',
  'java',
  'com',
  'example',
  'hms',
  'service',
  'impl',
  'MarDoseSchedule.java',
);

/** The phrases of `MarDoseSchedule.AS_NEEDED_PHRASES` (QA D1360: one list, read by `isAsNeeded`). */
export function serverAsNeededWords(java) {
  const start = java.indexOf('AS_NEEDED_PHRASES = List.of(');
  assert.ok(start >= 0, 'MarDoseSchedule.AS_NEEDED_PHRASES not found: the scan went blind');
  const body = java.slice(start, java.indexOf(');', start));
  return [...body.matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

/** The phrases of the portal helper's `AS_NEEDED_PHRASES`. */
export function portalAsNeededWords(ts) {
  const start = ts.indexOf('AS_NEEDED_PHRASES');
  assert.ok(start >= 0, 'portal AS_NEEDED_PHRASES not found: the scan went blind');
  const body = ts.slice(ts.indexOf('[', ts.indexOf('=', start)), ts.indexOf('];', start));
  return [...body.matchAll(/'([^']+)'/g)].map((m) => m[1]);
}

test(
  'the form reads as-needed with exactly the server words',
  { skip: !existsSync(SERVER) },
  () => {
    const server = serverAsNeededWords(readFileSync(SERVER, 'utf8'));
    const portal = portalAsNeededWords(readFileSync(PORTAL_HELPER, 'utf8'));
    assert.ok(server.length >= 18, `only ${server.length} server words read`);
    assert.deepEqual([...portal].sort(), [...server].sort());
  },
);

test(
  'a single dose (STAT) wins over as-needed on both sides',
  { skip: !existsSync(SERVER) },
  () => {
    const java = readFileSync(SERVER, 'utf8');
    const read = java.slice(java.indexOf('public static Reading read('));
    assert.ok(read.indexOf('"STAT"') < read.indexOf('isAsNeeded('), 'the server reads STAT first');
    const ts = readFileSync(PORTAL_HELPER, 'utf8');
    const fn = ts.slice(ts.indexOf('export function isAsNeededFrequency('));
    assert.ok(fn.indexOf("'STAT'") >= 0, 'the portal reads STAT');
    assert.ok(
      fn.indexOf("'STAT'") < fn.indexOf('AS_NEEDED_PHRASES'),
      'the portal reads STAT first',
    );
  },
);

test('guard: the parsers read the shapes', () => {
  assert.deepEqual(
    serverAsNeededWords(
      'static final List<String> AS_NEEDED_PHRASES = List.of(\n "PRN", "SI BESOIN");',
    ),
    ['PRN', 'SI BESOIN'],
  );
  assert.deepEqual(
    portalAsNeededWords(
      "export const AS_NEEDED_PHRASES: readonly string[] = [\n  'PRN',\n  'AU BESOIN',\n];",
    ),
    ['PRN', 'AU BESOIN'],
  );
});

// QA D1360: the as-needed vocabulary must cover the wordings prescribers use;
// a phrase dropped from both lists at once would pass the equality above.
test('the vocabulary holds the minimum FR/ES/EN wordings (QA D1360)', () => {
  const portal = portalAsNeededWords(readFileSync(PORTAL_HELPER, 'utf8'));
  for (const required of [
    'SI BESOIN',
    'AU BESOIN',
    'A LA DEMANDE',
    'SI NECESSAIRE',
    'EN CAS DE BESOIN',
    'SI DOULEUR',
    'SI FIEVRE',
    'PRN',
    'AS NEEDED',
    'WHEN REQUIRED',
    'IF NEEDED',
    'IF PAIN',
    'SI ES NECESARIO',
    'SEGUN NECESIDAD',
    'A DEMANDA',
    'EN CASO DE DOLOR',
  ]) {
    assert.ok(portal.includes(required), `as-needed vocabulary lost "${required}"`);
  }
});

// QA D1360: one fixture, both sides. The server test (MarDoseScheduleTest)
// and the portal spec read the same file; every phrase has a case in it.
test('every as-needed phrase has a case in the shared fixture', () => {
  const fixture = JSON.parse(readFileSync(CASES, 'utf8'));
  const folded = fixture.asNeeded.join('|').normalize('NFD').replace(/\p{M}/gu, '').toUpperCase();
  for (const phrase of portalAsNeededWords(readFileSync(PORTAL_HELPER, 'utf8'))) {
    assert.ok(folded.includes(phrase), `no fixture case for "${phrase}"`);
  }
});
