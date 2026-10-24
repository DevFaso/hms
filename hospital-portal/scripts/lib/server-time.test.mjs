// QA D731/D748, then D777/D784 guard. The API sends server instants
// (encounterDate, resultDate, createdAt, ...) as zone-less UTC wall times.
// `DatePipe` reads such a string as the BROWSER's local time, so a screen that
// skipped `serverTime` showed the UTC clock: the nurse station's triage cards
// read 15:33 in both Ouagadougou and New York while the chart screens, fixed
// one by one, were right. The first version of this guard listed field names
// and never saw `encounterDate`; it now requires `serverTime` in front of
// EVERY `date` pipe, in `.html` files and inline templates alike.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';
import { templatesIn } from './inline-templates.mjs';
import { walk } from './walk.mjs';
import { bareDatePipes, bareServerParses, WALL_CLOCK } from './server-time.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const operands = (text, file) => bareDatePipes(text, file).map((h) => h.operand);

test('the triage card line of the D777 re-test is caught', () => {
  assert.deepEqual(operands(`{{ e.encounterDate | date: 'shortTime' }}`), ['encounterDate']);
});

test('a time read through serverTime or fromServerDateTime passes', () => {
  assert.deepEqual(operands(`{{ e.encounterDate | serverTime | date: 'shortTime' }}`), []);
  assert.deepEqual(operands(`{{ fromServerDateTime(a.admissionDateTime) | date: 'short' }}`), []);
  assert.deepEqual(
    operands(`{{ cfg.lastExportAt ? (cfg.lastExportAt | serverTime | date: 'short') : '—' }}`),
    [],
  );
});

test('every field is caught, not only a listed one, and a signal is named by its call', () => {
  assert.deepEqual(
    operands(`{{ p.admittedAt | date }} {{ askedAt() | date: 'short' }} {{ x | async | date }}`),
    ['admittedAt', 'askedAt', 'async'],
  );
});

test('a wall-clock allowance covers only its own file', () => {
  const slot = `{{ slot.startAt | date: 'shortTime' }}`;
  assert.deepEqual(operands(slot, 'slot-admin/slot-admin.html'), []);
  assert.deepEqual(operands(slot, 'encounters/encounters.html'), ['startAt']);
});

test('every date pipe in every template reads server times as UTC', () => {
  const offenders = [];
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replace(/\\/g, '/');
    for (const hit of bareDatePipes(text, rel)) {
      offenders.push(`${rel}:${hit.line + lineOffset} ${hit.operand} | date`);
    }
  }
  assert.deepEqual(offenders, []);
});

test('every wall-clock allowance still matches a date pipe (allowances only shrink)', () => {
  const used = new Set();
  for (const { file, text } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replace(/\\/g, '/');
    for (const key of WALL_CLOCK.keys()) {
      const [path, field] = key.split(':');
      if (path === rel && new RegExp(String.raw`\b${field}\s*\|\s*date\b`).test(text)) {
        used.add(key);
      }
    }
  }
  assert.deepEqual(
    [...WALL_CLOCK.keys()].filter((key) => !used.has(key)),
    [],
  );
});

test('code parses a server instant with serverInstant, not new Date', () => {
  assert.deepEqual(
    bareServerParses(`const a = new Date(e.offerExpiresAt); const b = new Date(row.createdAt!);`),
    ['e.offerExpiresAt', 'row.createdAt!'],
  );
  assert.deepEqual(
    bareServerParses(`serverInstant(e.offerExpiresAt); new Date(); new Date(this.expiryDate)`),
    [],
  );
  const offenders = [];
  for (const file of walk(appDir, ['.ts'])) {
    const rel = relative(appDir, file).replace(/\\/g, '/');
    if (rel.endsWith('.spec.ts') || rel === 'shared/date-utils.ts') continue;
    for (const arg of bareServerParses(readFileSync(file, 'utf8'))) {
      offenders.push(`${rel} new Date(${arg})`);
    }
  }
  assert.deepEqual(offenders, []);
});
