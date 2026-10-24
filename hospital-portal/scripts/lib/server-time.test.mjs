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
import { existsSync, readFileSync } from 'node:fs';
import { templatesIn } from './inline-templates.mjs';
import { walk } from './walk.mjs';
import {
  bareDatePipes,
  bareServerParses,
  convertsBeforeSending,
  datetimeLocalFields,
  DATETIME_LOCAL_EXEMPT,
  LOCAL_PARSES,
  WALL_CLOCK,
} from './server-time.mjs';

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

const args = (source, file) => bareServerParses(source, file).map((h) => h.arg);

test('the #948 review misses are caught: any field, and Date.parse', () => {
  // consultations.ts isOverdue and the pharmacy's days-waiting count.
  assert.deepEqual(args(`return new Date(c.slaDueBy) < new Date();`), ['c.slaDueBy']);
  assert.deepEqual(args(`const parsed = Date.parse(raw);`), ['raw']);
  assert.deepEqual(
    args(`const a = new Date(e.offerExpiresAt); const b = new Date(row.createdAt!);`),
    ['e.offerExpiresAt', 'row.createdAt!'],
  );
  assert.deepEqual(args(`new Date(a ?? b)`), ['a ?? b']);
});

test('a value built in place, or already converted, passes', () => {
  assert.deepEqual(
    args(
      `serverInstant(e.offerExpiresAt); new Date(); new Date(2026, 5, 1);
       new Date(0); new Date('2026-01-01'); new Date(\`\${d}T00:00:00\`);
       new Date(start + 'T00:00:00'); new Date(Date.now() - 3600);
       new Date(SERVER_TIME.transform(iso));`,
    ),
    [],
  );
});

test('a local-parse allowance covers only its own file', () => {
  assert.deepEqual(args(`new Date(iso)`, 'on-call/on-call.ts'), []);
  assert.deepEqual(args(`new Date(iso)`, 'chat/chat.ts'), ['iso']);
});

test('code parses a server time with serverInstant, not new Date or Date.parse', () => {
  const offenders = [];
  const used = new Set();
  for (const file of walk(appDir, ['.ts'])) {
    const rel = relative(appDir, file).replace(/\\/g, '/');
    if (rel.endsWith('.spec.ts') || rel === 'shared/date-utils.ts') continue;
    const source = readFileSync(file, 'utf8');
    for (const hit of bareServerParses(source, rel)) {
      offenders.push(`${rel}:${hit.line} Date(${hit.arg})`);
    }
    for (const hit of bareServerParses(source)) {
      if (LOCAL_PARSES.has(`${rel}:${hit.arg}`)) used.add(`${rel}:${hit.arg}`);
    }
  }
  assert.deepEqual(offenders, []);
  // Allowances only shrink: each must still name a live site.
  assert.deepEqual(
    [...LOCAL_PARSES.keys()].filter((key) => !used.has(key)),
    [],
  );
});

test('a datetime-local field is found by its last name', () => {
  assert.deepEqual(
    datetimeLocalFields(
      `<input id="a" type="datetime-local" [(ngModel)]="orderForm.scheduledDatetime" />
       <input
         type="datetime-local"
         [ngModel]="formCollectedAt()"
         (ngModelChange)="formCollectedAt.set($event)" />
       <input type="datetime-local" [ngModel]="form().eventOccurredAt ?? ''" />
       <input type="datetime-local" />`,
    ).map((f) => f.field),
    ['scheduledDatetime', 'formCollectedAt', 'eventOccurredAt', null],
  );
});

test('a datetime-local value is converted before it is sent', () => {
  // procedure-orders.ts create, mortality.ts and postpartum-tab.ts before #948 review.
  assert.equal(convertsBeforeSending(`create(form)`, 'scheduledDatetime'), false);
  assert.equal(convertsBeforeSending(`diedAt: this.form.diedAt,`, 'diedAt'), false);
  assert.equal(
    convertsBeforeSending(
      `create(withServerDateTimes(form, ['scheduledDatetime']))`,
      'scheduledDatetime',
    ),
    true,
  );
  assert.equal(
    convertsBeforeSending(`diedAt: toServerDateTime(this.form.diedAt),`, 'diedAt'),
    true,
  );
  // A neighbour's conversion does not count for the field.
  assert.equal(
    convertsBeforeSending(`toServerDateTime(this.scheduleDatetime)`, 'scheduledDatetime'),
    false,
  );
});

test('every datetime-local input reaches the API as UTC', () => {
  const offenders = [];
  const used = new Set();
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replace(/\\/g, '/');
    const component = file.endsWith('.html') ? `${file.slice(0, -'.html'.length)}.ts` : file;
    const source = existsSync(component) ? readFileSync(component, 'utf8') : '';
    for (const { line, field } of datetimeLocalFields(text)) {
      const key = `${rel}:${field}`;
      if (field && convertsBeforeSending(source, field)) continue;
      if (DATETIME_LOCAL_EXEMPT.has(key)) {
        used.add(key);
        continue;
      }
      offenders.push(`${rel}:${line + lineOffset} ${field ?? '(no binding found)'}`);
    }
  }
  assert.deepEqual(offenders, []);
  // Allowances only shrink: each must still name an unconverted input.
  assert.deepEqual(
    [...DATETIME_LOCAL_EXEMPT.keys()].filter((key) => !used.has(key)),
    [],
  );
});
