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

/** The words `isAsNeeded` matches in the server file. */
export function serverAsNeededWords(java) {
  const start = java.indexOf('private static boolean isAsNeeded(');
  assert.ok(start >= 0, 'MarDoseSchedule.isAsNeeded not found: the scan went blind');
  const body = java.slice(start, java.indexOf('}', start));
  return [...body.matchAll(/contains\("([^"]+)"\)/g)].map((m) => m[1]);
}

/** The words the portal helper matches. */
export function portalAsNeededWords(ts) {
  return [...ts.matchAll(/includes\('([^']+)'\)/g)].map((m) => m[1]).filter((w) => w !== 'STAT');
}

test(
  'the form reads as-needed with exactly the server words',
  { skip: !existsSync(SERVER) },
  () => {
    const server = serverAsNeededWords(readFileSync(SERVER, 'utf8'));
    const portal = portalAsNeededWords(readFileSync(PORTAL_HELPER, 'utf8'));
    assert.ok(server.length >= 4, `only ${server.length} server words read`);
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
    assert.ok(ts.indexOf("'STAT'") < ts.indexOf("'PRN'"), 'the portal reads STAT first');
  },
);

test('guard: the parsers read the shapes', () => {
  assert.deepEqual(
    serverAsNeededWords(
      'private static boolean isAsNeeded(String u) {\n return u.contains("PRN") || u.contains("SI BESOIN");\n}',
    ),
    ['PRN', 'SI BESOIN'],
  );
  assert.deepEqual(portalAsNeededWords("upper.includes('STAT'); upper.includes('PRN')"), ['PRN']);
});
