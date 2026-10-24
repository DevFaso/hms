// QA D731/D748 guard. The API sends server instants (resultDate, createdAt,
// signedAt, ...) as zone-less UTC wall times. `DatePipe` reads such a string
// as the BROWSER's local time, so the in-basket and notifications (which went
// through `serverTime`) and lab-results (which did not) showed the same result
// four hours apart on a workstation set to America/Toronto. Every template now
// pipes these instant fields through `serverTime` before `date`.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/** Server-stamped instant fields (LocalDateTime in UTC on the API). */
const INSTANT_FIELDS = [
  'resultDate',
  'releasedAt',
  'acknowledgedAt',
  'criticalReadBackAt',
  'resultedAt',
  'createdAt',
  'updatedAt',
  'eventTimestamp',
  'signedAt',
  'collectedAt',
  'orderDatetime',
  'receivedAt',
  'verifiedAt',
  'completedAt',
  'sentAt',
  'readAt',
];
const BARE_DATE = new RegExp(String.raw`\b(${INSTANT_FIELDS.join('|')})\s*\|\s*date\b`, 'g');

test('the guard regex catches a bare instant and spares a piped one', () => {
  assert.equal([...'{{ a.createdAt | date }}'.matchAll(BARE_DATE)].length, 1);
  assert.equal([...'{{ a.createdAt | serverTime | date }}'.matchAll(BARE_DATE)].length, 0);
});

test('server instants go through serverTime before the date pipe', () => {
  const offenders = [];
  for (const file of walk(appDir, ['.html'])) {
    const text = readFileSync(file, 'utf8');
    for (const m of text.matchAll(BARE_DATE)) {
      const line = text.slice(0, m.index).split('\n').length;
      offenders.push(`${relative(appDir, file)}:${line} ${m[0]}`);
    }
  }
  assert.deepEqual(offenders, []);
});
