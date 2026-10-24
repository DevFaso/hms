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
  BUILT_DATE_TIME_EXEMPT,
  convertsBeforeSending,
  datetimeLocalFields,
  DATETIME_LOCAL_EXEMPT,
  LOCAL_INPUT_EXEMPT,
  LOCAL_PARSES,
  localInputValuesOffInputs,
  SLICED_DAY_EXEMPT,
  slicedServerDays,
  unconvertedDateTimes,
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

const built = (source, file) => unconvertedDateTimes(source, file).map((h) => h.text);

test('a date and time built from a picked day and sent raw is caught (#948 review)', () => {
  // encounters.ts submitForm, my-family-access grantAccess and the nurse
  // station's triage "today" bound before the fix.
  assert.deepEqual(built(`const p = { encounterDate: this.form.encounterDate + 'T00:00:00' };`), [
    "this.form.encounterDate + 'T00:00:00'",
  ]);
  assert.deepEqual(built('this.form.expiresAt = `${this.expiresOn}T23:59:59`;'), [
    '`${this.expiresOn}T23:59:59`',
  ]);
  assert.deepEqual(built('const fromDate = `${y}-${pad(m)}-${pad(d)}T00:00:00`;'), [
    '`${y}-${pad(m)}-${pad(d)}T00:00:00`',
  ]);
  assert.deepEqual(built('const at = `${day}T${time}`; const b = day + "T" + time;'), [
    '`${day}T${time}`',
  ]);
});

test('a built date and time parsed locally or converted in place passes', () => {
  assert.deepEqual(
    built(
      `new Date(start + 'T00:00:00'); new Date(\`\${d}T23:59:59\`).toISOString();
       toServerDateTime(\`\${day}T00:00\`); toServerDateTime((day + 'T00:00'));
       const id = \`ALERT\${n}\`; const s = 'PAT' + id;
       \`\${a.d}T\${a.t}\`.localeCompare(\`\${b.d}T\${b.t}\`);`,
    ),
    [],
  );
});

test('no date and time string is built and sent unconverted', () => {
  const offenders = [];
  const used = new Set();
  for (const file of walk(appDir, ['.ts'])) {
    const rel = relative(appDir, file).replace(/\\/g, '/');
    if (rel.endsWith('.spec.ts') || rel === 'shared/date-utils.ts') continue;
    const source = readFileSync(file, 'utf8');
    for (const hit of unconvertedDateTimes(source, rel)) {
      offenders.push(`${rel}:${hit.line} ${hit.text}`);
    }
    for (const hit of unconvertedDateTimes(source)) {
      if (BUILT_DATE_TIME_EXEMPT.has(`${rel}:${hit.text}`)) used.add(`${rel}:${hit.text}`);
    }
  }
  assert.deepEqual(offenders, []);
  assert.deepEqual(
    [...BUILT_DATE_TIME_EXEMPT.keys()].filter((key) => !used.has(key)),
    [],
  );
});

const sliced = (source, file) => slicedServerDays(source, file).map((h) => h.field);

test('a calendar day cut from a server field is caught (#948 review)', () => {
  // discharge.ts encounterLabel and encounters.ts openEdit before the fix.
  assert.deepEqual(
    sliced(
      `const a = e.encounterDate ? e.encounterDate.substring(0, 10) : '';
       const b = enc.encounterDate?.split('T')[0] || '';
       const c = row.createdAt!.slice(0, 10);`,
    ),
    ['e.encounterDate', 'enc.encounterDate', 'row.createdAt'],
  );
});

test('the UTC day of a local Date is caught too', () => {
  // appointment-list todayIso and staff-detail toISODate before #948 review.
  assert.deepEqual(
    sliced(`new Date().toISOString().split('T')[0]; d.toISOString().slice(0, 10);`),
    ['new Date().toISOString()', 'd.toISOString()'],
  );
});

test('a non-day slice, or a converted value, passes', () => {
  assert.deepEqual(
    sliced(
      `[n, ...list].slice(0, 10); this.items.slice(0, 5); name.split(' ')[0];
       (id ?? '').replace(/-/g, '').substring(0, 10); d.toISOString().slice(0, 19);
       serverLocalDate(e.encounterDate); localDateString(d);`,
    ),
    [],
  );
});

test('a sliced-day allowance covers only its own file', () => {
  const src = `inv.invoiceDate?.substring(0, 10)`;
  assert.deepEqual(sliced(src, 'billing/billing.ts'), []);
  assert.deepEqual(sliced(src, 'discharge/discharge.ts'), ['inv.invoiceDate']);
});

test('no screen cuts the UTC day out of a server time', () => {
  const offenders = [];
  const used = new Set();
  for (const file of walk(appDir, ['.ts'])) {
    const rel = relative(appDir, file).replace(/\\/g, '/');
    if (rel.endsWith('.spec.ts') || rel === 'shared/date-utils.ts') continue;
    const source = readFileSync(file, 'utf8');
    for (const hit of slicedServerDays(source, rel)) {
      offenders.push(`${rel}:${hit.line} ${hit.field}`);
    }
    for (const hit of slicedServerDays(source)) {
      if (SLICED_DAY_EXEMPT.has(`${rel}:${hit.field}`)) used.add(`${rel}:${hit.field}`);
    }
  }
  assert.deepEqual(offenders, []);
  assert.deepEqual(
    [...SLICED_DAY_EXEMPT.keys()].filter((key) => !used.has(key)),
    [],
  );
});

const offInputs = (source, fields = [], file = '') =>
  localInputValuesOffInputs(source, new Set(fields), file).map((h) => h.target);

test('a wall-clock helper value sent outside a datetime-local input is caught (#948 review)', () => {
  // procedure-orders.ts submitConsent before the fix: no input, no built string.
  assert.deepEqual(
    offInputs(`this.applyUpdate(order, { consentObtainedAt: nowLocalDatetime() }, 'K');`, [
      'scheduleDatetime',
    ]),
    ['consentObtainedAt'],
  );
  assert.deepEqual(offInputs(`this.form.at = toLocalInputValue(v);`), ['at']);
  assert.deepEqual(offInputs(`class C { f() { return nowLocalDatetime(); } }`), ['return in f']);
  assert.deepEqual(offInputs(`send(nowLocalDatetime());`), ['nowLocalDatetime()']);
});

test('a converted value, or the default of a tracked input, passes', () => {
  assert.deepEqual(
    offInputs(
      `x = { consentObtainedAt: toServerDateTime(nowLocalDatetime()) };
       y = { resultDate: r.resultDate ? toLocalInputValue(r.resultDate) : nowLocalDatetime() };
       this.screeningAdministeredAt = nowLocalDatetime();`,
      ['resultDate', 'screeningAdministeredAt'],
    ),
    [],
  );
  // The condition of a choice is not where the value goes.
  assert.deepEqual(offInputs(`if (nowLocalDatetime() ? a : b) {}`), ['nowLocalDatetime()']);
});

test('a local-input allowance covers only its own file', () => {
  const source = `class C { toLocalInput(iso: string) { return nowLocalDatetime(new Date(iso)); } }`;
  assert.deepEqual(offInputs(source, [], 'on-call/on-call.ts'), []);
  assert.deepEqual(offInputs(source, [], 'other/other.ts'), ['return in toLocalInput']);
});

test('wall-clock helper values only fill datetime-local inputs', () => {
  const fieldsOf = new Map();
  for (const { file, text } of templatesIn(appDir)) {
    const component = file.endsWith('.html') ? `${file.slice(0, -'.html'.length)}.ts` : file;
    const fields = fieldsOf.get(component) ?? new Set();
    for (const { field } of datetimeLocalFields(text)) if (field) fields.add(field);
    fieldsOf.set(component, fields);
  }
  const offenders = [];
  const used = new Set();
  for (const file of walk(appDir, ['.ts'])) {
    const rel = relative(appDir, file).replace(/\\/g, '/');
    if (rel.endsWith('.spec.ts') || rel === 'shared/date-utils.ts') continue;
    const source = readFileSync(file, 'utf8');
    const fields = fieldsOf.get(file) ?? new Set();
    for (const hit of localInputValuesOffInputs(source, fields, rel)) {
      offenders.push(`${rel}:${hit.line} ${hit.target}`);
    }
    for (const hit of localInputValuesOffInputs(source, fields)) {
      if (LOCAL_INPUT_EXEMPT.has(`${rel}:${hit.target}`)) used.add(`${rel}:${hit.target}`);
    }
  }
  assert.deepEqual(offenders, []);
  assert.deepEqual(
    [...LOCAL_INPUT_EXEMPT.keys()].filter((key) => !used.has(key)),
    [],
  );
});
