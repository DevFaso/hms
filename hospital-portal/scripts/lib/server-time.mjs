/**
 * Every `date` pipe in a template that does not read a server time as UTC.
 *
 * The API stamps its times (`LocalDateTime` on a UTC server) and sends them
 * with no zone: `2026-10-24T15:33:17`. `DatePipe` reads a zone-less string as
 * the BROWSER's local time, so a reader outside UTC sees the UTC clock — the
 * nurse station's triage cards showed 15:33 in both Ouagadougou and New York
 * (QA D777/D784). `serverTime` marks such a string as UTC and leaves anything
 * else (a date without a time, a value with a zone, a Date, a number) as it
 * is, so it is safe in front of EVERY `date` pipe:
 *
 *     {{ e.encounterDate | serverTime | date: 'shortTime' }}
 *
 * The first guard (D731/D748) listed the field NAMES that needed the pipe, so
 * every field missing from the list (encounterDate, admittedAt,
 * lastVitalsTime, ...) slipped past it, which is how the triage cards stayed
 * wrong after the chart screens were fixed. This gate asks the opposite
 * question: a `date` pipe is accepted only when `serverTime` (or
 * `fromServerDateTime(...)`, the same conversion in code) is in front of it,
 * or when its field is a known wall-clock value in `WALL_CLOCK`.
 */
import ts from 'typescript';

/**
 * Fields that are NOT server instants: wall-clock times built from a
 * session template (a clinic slot at 08:00 is 08:00 at the hospital, whoever
 * reads it), keyed `<path under src/app>:<field>`. Allowances may only
 * shrink.
 */
export const WALL_CLOCK = new Map([
  [
    'slot-admin/slot-admin.html:startAt',
    'slot built from a session template (LocalDate + LocalTime)',
  ],
  [
    'slot-admin/slot-admin.html:endAt',
    'slot built from a session template (LocalDate + LocalTime)',
  ],
  [
    'reception/waitlist-panel/waitlist-panel.component.html:startAt',
    'slot built from a session template (LocalDate + LocalTime)',
  ],
  [
    'reception/waitlist-panel/waitlist-panel.component.html:offeredSlotStartAt',
    "copy of the offered slot's wall-clock start",
  ],
  [
    'referrals/referrals.html:scheduledAppointmentAt',
    "booked as the Appointment's local date and start time (#948 review)",
  ],
]);

const DATE_PIPE = /\|\s*date\b/g;

/** The index of the `(` that opens the group closed by the `)` at `close`. */
function openingParen(text, close) {
  let depth = 0;
  for (let i = close; i >= 0; i--) {
    if (text[i] === ')') depth++;
    else if (text[i] === '(' && --depth === 0) return i;
  }
  return -1;
}

/**
 * The verdict for the `date` pipe whose `|` is at `at`: null when it reads a
 * server time as UTC, otherwise the field (or call) it formats.
 */
export function operandOf(text, at) {
  const before = text.slice(0, at).trimEnd();
  if (/\|\s*serverTime$/.test(before)) return null;
  if (before.endsWith(')')) {
    const open = openingParen(before, before.length - 1);
    const callee = /([\w$]+)\s*$/.exec(before.slice(0, Math.max(open, 0)));
    if (callee?.[1] === 'fromServerDateTime') return null;
    // `askedAt()` (a signal) is named by its callee, `(x ? a : b)` by its text.
    if (callee) return callee[1];
    return before.slice(open);
  }
  const field = /([\w$]+)$/.exec(before);
  return field ? field[1] : before.slice(-40);
}

/**
 * The `date` pipes of one template that skip `serverTime`, minus the
 * wall-clock allowances of `file` (its path under src/app, `/`-separated).
 *
 * @returns {{ index: number, line: number, operand: string }[]}
 */
export function bareDatePipes(text, file = '') {
  const found = [];
  for (const m of text.matchAll(DATE_PIPE)) {
    const operand = operandOf(text, m.index);
    if (operand === null || WALL_CLOCK.has(`${file}:${operand}`)) continue;
    found.push({ index: m.index, line: text.slice(0, m.index).split('\n').length, operand });
  }
  return found;
}

/**
 * The same mistake in code: `new Date(row.offerExpiresAt)` reads a zone-less
 * server time as local time (the waitlist's "offer expired" check and the
 * profile's last login were off by the reader's offset). The first version of
 * this check matched argument NAMES (`...At`, `...Time`, ...) and so missed
 * `new Date(c.slaDueBy)` (the consultation overdue badge) and every
 * `Date.parse(raw)` (the pharmacy's days-waiting count) — the name-list
 * mistake the template gate had just been rebuilt to avoid (#948 review).
 *
 * So every one-argument `new Date(x)` and every `Date.parse(x)` outside
 * shared/date-utils is a finding, unless `x` is built right there (a number, a
 * string or template literal, `a + 'T00:00:00'`, `Date.now() - n`) or already
 * went through `SERVER_TIME.transform`. A value from anywhere else goes
 * through `serverInstant` / `serverDateOrInstant`, or is listed in
 * `LOCAL_PARSES` with the reason it is not a server time.
 */

/**
 * `new Date(x)` / `Date.parse(x)` sites whose argument is not a server time,
 * keyed `<path under src/app>:<argument as written>`. Allowances may only
 * shrink.
 */
export const LOCAL_PARSES = new Map([
  ['analytics/kpi-cards/kpi-cards.component.ts:today', 'copy of a Date object'],
  ['appointments/calendar/appointment-calendar.component.ts:d', 'copy of a Date object'],
  ['scheduling/scheduling.ts:now', 'copy of a Date object'],
  ['scheduling/scheduling.ts:monday', 'copy of a Date object'],
  ['staff/staff-detail.ts:now', 'copy of a Date object'],
  ['staff/staff-detail.ts:monday', 'copy of a Date object'],
  [
    'nurse-station/nurse-station.ts:value',
    'datetime-local value (wall clock), converted to UTC right here',
  ],
  ['on-call/on-call.ts:this.form.startLocal', 'datetime-local value (wall clock), sent zoned'],
  ['on-call/on-call.ts:this.form.endLocal', 'datetime-local value (wall clock), sent zoned'],
  ['on-call/on-call.ts:iso', 'OffsetDateTime from the API: carries its zone'],
  [
    'organizations/organization-detail.ts:this.modalPurgeAt()',
    'datetime-local value (wall clock), sent zoned',
  ],
  ['services/impersonation.service.ts:expiresAtIso', 'Instant from the API: ends in Z'],
]);

/** An argument the call site builds itself, so it cannot be a server string. */
function builtInPlace(arg) {
  if (
    ts.isNumericLiteral(arg) ||
    ts.isStringLiteral(arg) ||
    ts.isNoSubstitutionTemplateLiteral(arg) ||
    ts.isTemplateExpression(arg)
  ) {
    return true;
  }
  if (ts.isBinaryExpression(arg)) {
    const op = arg.operatorToken.kind;
    return op === ts.SyntaxKind.PlusToken || op === ts.SyntaxKind.MinusToken;
  }
  return ts.isCallExpression(arg) && arg.expression.getText() === 'SERVER_TIME.transform';
}

/**
 * Every `new Date(x)` (one argument) and `Date.parse(x)` of a TypeScript
 * source whose `x` comes from elsewhere, minus the `LOCAL_PARSES` allowances
 * of `file` (its path under src/app, `/`-separated).
 *
 * @returns {{ line: number, arg: string }[]}
 */
export function bareServerParses(source, file = '') {
  if (!source.includes('Date')) return [];
  const sf = ts.createSourceFile('x.ts', source, ts.ScriptTarget.Latest, true);
  const found = [];
  const visit = (node) => {
    let arg;
    if (
      ts.isNewExpression(node) &&
      node.expression.getText() === 'Date' &&
      node.arguments?.length === 1
    ) {
      arg = node.arguments[0];
    } else if (ts.isCallExpression(node) && node.expression.getText() === 'Date.parse') {
      arg = node.arguments[0];
    }
    if (arg && !builtInPlace(arg)) {
      const text = arg.getText().replace(/\s+/g, ' ');
      if (!LOCAL_PARSES.has(`${file}:${text}`)) {
        found.push({ line: sf.getLineAndCharacterOfPosition(node.getStart()).line + 1, arg: text });
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return found;
}

/**
 * The other direction (#948 review). A `datetime-local` input gives the
 * user's wall clock with no zone, and the screens now read the server's
 * times as UTC; a form that sent the picker value raw therefore stored the
 * wall clock as if it were UTC, and the row it had just created showed
 * shifted by the reader's offset (procedure scheduling, time of death, the
 * postpartum screening time). Every `datetime-local` binding must reach the
 * API through `toServerDateTime(...)` or `withServerDateTimes(..., ['field'])`
 * in its component, or be listed here with the reason it need not be.
 * Keyed `<template path under src/app>:<bound field>`. Allowances may only
 * shrink.
 */
export const DATETIME_LOCAL_EXEMPT = new Map([
  [
    'referrals/referrals.html:scheduleAppointmentTime',
    "wall clock by design: booked as the Appointment's local date and start time",
  ],
  ['nurse-station/nurse-station.html:dueAt', 'sent as UTC by localInputToUtcIso (toISOString)'],
  ['on-call/on-call.html:startLocal', 'sent zoned (toISOString) to an OffsetDateTime'],
  ['on-call/on-call.html:endLocal', 'sent zoned (toISOString) to an OffsetDateTime'],
  ['organizations/organization-detail.html:modalPurgeAt', 'sent zoned (toISOString)'],
  [
    'super-admin/audit-search/audit-search.html:fromDate',
    'converted by toIsoDateTime(), which calls toServerDateTime',
  ],
  [
    'super-admin/audit-search/audit-search.html:toDate',
    'converted by toIsoDateTime(), which calls toServerDateTime',
  ],
]);

const DATETIME_LOCAL_INPUT = /<input\b[^>]*?type="datetime-local"[^>]*>/gs;
const BINDING =
  /\[\(ngModel\)\]="([^"]+)"|\[ngModel\]="([^"]+)"|formControlName="([^"]+)"|\[value\]="([^"]+)"/;

/**
 * The field bound to each `datetime-local` input of one template: the last
 * name of the binding (`orderForm.scheduledDatetime` → `scheduledDatetime`,
 * `formCollectedAt()` → `formCollectedAt`), or null when none is found.
 *
 * @returns {{ line: number, field: string | null }[]}
 */
export function datetimeLocalFields(text) {
  const found = [];
  for (const m of text.matchAll(DATETIME_LOCAL_INPUT)) {
    const bound = BINDING.exec(m[0])?.slice(1).find(Boolean);
    const field = bound ? (/([\w$]+)(\(\))?(\s*\?\?.*)?$/.exec(bound.trim())?.[1] ?? null) : null;
    found.push({ line: text.slice(0, m.index).split('\n').length, field });
  }
  return found;
}

/** Whether `source` converts `field` with toServerDateTime or withServerDateTimes. */
export function convertsBeforeSending(source, field) {
  return (
    new RegExp(String.raw`toServerDateTime\([^;]*\b${field}\b`).test(source) ||
    new RegExp(String.raw`withServerDateTimes\([^;]*'${field}'`).test(source)
  );
}
