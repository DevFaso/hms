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
    'reception/waitlist-panel/waitlist-panel.component.html:requestedDateFrom',
    'LocalDate the desk typed (a calendar day, no clock): QA D1119',
  ],
  [
    'reception/waitlist-panel/waitlist-panel.component.html:requestedDateTo',
    'LocalDate the desk typed (a calendar day, no clock): QA D1119',
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

const LIMIT_BINDING =
  /\[(?:attr\.)?(?:min|max)\]="([^"]+)"|\b(?:min|max)="\{\{\s*([^"}]+?)\s*\}\}"/g;

/**
 * The fields bound to the `[min]` / `[max]` limit of each `datetime-local`
 * input of one template (`[max]="maxOnsetAt"` → `maxOnsetAt`). A limit is
 * compared by the browser with the picker's own wall-clock value, so it must
 * be wall clock too: these fields are local-input targets for the helper gate
 * below, never values sent to the API.
 *
 * @returns {{ line: number, field: string }[]}
 */
export function datetimeLocalLimitFields(text) {
  const found = [];
  for (const m of text.matchAll(DATETIME_LOCAL_INPUT)) {
    const line = text.slice(0, m.index).split('\n').length;
    for (const b of m[0].matchAll(LIMIT_BINDING)) {
      const bound = (b[1] ?? b[2]).trim();
      const field = /([\w$]+)(\(\))?$/.exec(bound)?.[1];
      if (field) found.push({ line, field });
    }
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

/**
 * The same direction in code (#948 review). A date and time built from a
 * picked day (`day + 'T00:00:00'`, `` `${day}T23:59:59` ``) is the reader's
 * wall clock with no zone; sent as is, the API stored it as if it were UTC,
 * and the screens that read it back through `serverTime` showed the day
 * before (an encounter created in New York) or after (a proxy expiry east of
 * UTC). Every such string must be parsed locally right there
 * (`new Date(...)`) or sent through `toServerDateTime(...)`, or be listed
 * here with the reason it need not be. Keyed `<path under src/app>:<text as
 * written>`. Allowances may only shrink.
 */
export const BUILT_DATE_TIME_EXEMPT = new Map([
  [
    "patient-portal/my-appointments/my-appointments.component.ts:`${a.date ?? ''}T${a.rawStartTime ?? ''}`",
    'sort key of a visit (wall-clock date and start time), never sent',
  ],
]);

const TIME_TEXT = /T\d{2}:\d{2}/;

/** Whether the literal parts of a `+` chain or template carry a time (`T08:00`, or `T${time}`). */
function buildsDateTime(node) {
  if (ts.isTemplateExpression(node)) {
    const parts = [node.head.text, ...node.templateSpans.map((s) => s.literal.text)];
    return parts.some(
      (text, i) => TIME_TEXT.test(text) || (i < parts.length - 1 && /(^|\d)T$/.test(text)),
    );
  }
  if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    const literal = (n) =>
      (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) && TIME_TEXT.test(n.text);
    const sides = (n) =>
      ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.PlusToken
        ? [...sides(n.left), ...sides(n.right)]
        : [n];
    return sides(node).some((n) => literal(n) || buildsDateTime(n));
  }
  return false;
}

/** The parent of `node`, past any parentheses. */
function parentOf(node) {
  let parent = node.parent;
  while (parent && ts.isParenthesizedExpression(parent)) parent = parent.parent;
  return parent;
}

/**
 * Whether a built date and time is parsed locally, converted, or only
 * compared (a `localeCompare` sort key) where it is built.
 */
function convertedInPlace(node) {
  const parent = parentOf(node);
  if (!parent) return false;
  if (ts.isNewExpression(parent) && parent.expression.getText() === 'Date') return true;
  if (ts.isPropertyAccessExpression(parent) && parent.name.text === 'localeCompare') return true;
  if (
    ts.isCallExpression(parent) &&
    ts.isPropertyAccessExpression(parent.expression) &&
    parent.expression.name.text === 'localeCompare'
  ) {
    return true;
  }
  return ts.isCallExpression(parent) && parent.expression.getText() === 'toServerDateTime';
}

/**
 * Every date and time string built in a TypeScript source that is neither
 * parsed locally nor converted with `toServerDateTime` where it is built,
 * minus the `BUILT_DATE_TIME_EXEMPT` allowances of `file`.
 *
 * @returns {{ line: number, text: string }[]}
 */
export function unconvertedDateTimes(source, file = '') {
  if (!/T\d{2}:\d{2}|T\$\{/.test(source)) return [];
  const sf = ts.createSourceFile('x.ts', source, ts.ScriptTarget.Latest, true);
  const found = [];
  const visit = (node) => {
    const isPlus =
      ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken;
    if (isPlus || ts.isTemplateExpression(node)) {
      const parent = parentOf(node);
      const innerOfChain =
        isPlus &&
        parent &&
        ts.isBinaryExpression(parent) &&
        parent.operatorToken.kind === ts.SyntaxKind.PlusToken;
      if (!innerOfChain && buildsDateTime(node)) {
        const text = node.getText().replace(/\s+/g, ' ');
        if (!convertedInPlace(node) && !BUILT_DATE_TIME_EXEMPT.has(`${file}:${text}`)) {
          found.push({ line: sf.getLineAndCharacterOfPosition(node.getStart()).line + 1, text });
        }
        return;
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return found;
}

/**
 * And reading one back (#948 review): cutting the calendar day out of a
 * server time (`e.encounterDate.substring(0, 10)`, `.slice(0, 10)`,
 * `.split('T')[0]`) gives its UTC day, so a 21:00 New York visit was listed
 * under the next day and an edit form pre-filled the wrong date. A server
 * value goes through `serverLocalDate(...)` instead. The same cut from
 * `x.toISOString()` is the UTC day of a local Date — "today" was tomorrow
 * after 20:00 in New York, and a staff week grid built from local midnights
 * shifted by a day east of UTC — so it is caught too; `localDateString(x)`
 * gives the reader's day. A value read from a field (`a.b`, `a?.b!`) or a
 * `toISOString()` call is checked; other receivers (an array, a call that
 * builds an id) are not dates. Fields that are not server times (a
 * `LocalDate` sent as a string, a wall-clock booking) are listed here with
 * the reason, keyed `<path under src/app>:<receiver as written>`. Allowances
 * may only shrink.
 */
export const SLICED_DAY_EXEMPT = new Map([
  ['billing/billing.ts:inv.invoiceDate', 'LocalDate sent as a string: already a calendar day'],
  ['billing/billing.ts:inv.dueDate', 'LocalDate sent as a string: already a calendar day'],
  [
    'services/patient-portal.service.ts:f.appointmentTime',
    'booked follow-up visit time (wall clock), used only when appointmentDate is absent',
  ],
]);

/** `x`, `x!` or `(x)` down to the expression they wrap. */
function unwrap(node) {
  let n = node;
  while (ts.isNonNullExpression(n) || ts.isParenthesizedExpression(n)) n = n.expression;
  return n;
}

const isNumber = (n, value) => ts.isNumericLiteral(n) && Number(n.text) === value;

/** The field a `.substring(0, 10)` / `.slice(0, 10)` / `.split('T')[0]` cuts a day from, or null. */
function slicedField(node) {
  let call;
  if (
    ts.isCallExpression(node) &&
    ts.isPropertyAccessExpression(node.expression) &&
    ['substring', 'slice'].includes(node.expression.name.text) &&
    node.arguments.length === 2 &&
    isNumber(node.arguments[0], 0) &&
    isNumber(node.arguments[1], 10)
  ) {
    call = node;
  } else if (
    ts.isElementAccessExpression(node) &&
    isNumber(node.argumentExpression, 0) &&
    ts.isCallExpression(unwrap(node.expression))
  ) {
    const split = unwrap(node.expression);
    const arg = split.arguments[0];
    if (
      ts.isPropertyAccessExpression(split.expression) &&
      split.expression.name.text === 'split' &&
      arg &&
      (ts.isStringLiteral(arg) || ts.isNoSubstitutionTemplateLiteral(arg)) &&
      arg.text === 'T'
    ) {
      call = split;
    }
  }
  if (!call) return null;
  const receiver = unwrap(call.expression.expression);
  if (ts.isPropertyAccessExpression(receiver)) return receiver;
  const isoString =
    ts.isCallExpression(receiver) &&
    ts.isPropertyAccessExpression(receiver.expression) &&
    receiver.expression.name.text === 'toISOString';
  return isoString ? receiver : null;
}

/**
 * Every calendar day cut from a field of a TypeScript source, minus the
 * `SLICED_DAY_EXEMPT` allowances of `file`.
 *
 * @returns {{ line: number, field: string }[]}
 */
export function slicedServerDays(source, file = '') {
  if (!/substring\(0|slice\(0|split\(/.test(source)) return [];
  const sf = ts.createSourceFile('x.ts', source, ts.ScriptTarget.Latest, true);
  const found = [];
  const visit = (node) => {
    const field = slicedField(node);
    if (field) {
      const text = field.getText().replace(/\s+/g, ' ');
      if (!SLICED_DAY_EXEMPT.has(`${file}:${text}`)) {
        found.push({
          line: sf.getLineAndCharacterOfPosition(node.getStart()).line + 1,
          field: text,
        });
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return found;
}

/**
 * And the helpers that make a wall-clock value (#948 review). `nowLocalDatetime()`
 * and `toLocalInputValue(...)` give the reader's wall clock with no zone: right
 * for a `datetime-local` input, whose value the gate above already requires to
 * be converted before it is sent, and wrong anywhere else. The consent time of
 * a procedure order was set straight from `nowLocalDatetime()` in the update
 * payload, so it reached the API as wall clock, and the detail (which reads it
 * back through `serverTime`) showed a 14:00 New York consent at 10:00. No gate
 * saw it: there was no input to check and no string built in place. So a call
 * to either helper is accepted only when it is converted right there
 * (`toServerDateTime(nowLocalDatetime())`) or is the default of a field a
 * `datetime-local` input of the component binds (`resultDate: nowLocalDatetime()`
 * with `[(ngModel)]="form.resultDate"`), or is listed here with the reason.
 * Keyed `<path under src/app>:<target>`, where the target is the field
 * assigned, or `return in <method>` for a value returned. Allowances may only
 * shrink.
 */
export const LOCAL_INPUT_EXEMPT = new Map([
  [
    'on-call/on-call.ts:return in toLocalInput',
    'fills the startLocal/endLocal datetime-local inputs, which are sent zoned',
  ],
  [
    'super-admin/audit-search/audit-search.ts:return in fromIsoForInput',
    'fills the fromDate/toDate datetime-local inputs, converted by toIsoDateTime()',
  ],
]);

const LOCAL_INPUT_HELPERS = new Set(['nowLocalDatetime', 'toLocalInputValue']);

/** The name of the method or function that encloses `node`, or `?`. */
function enclosingName(node) {
  for (let n = node.parent; n; n = n.parent) {
    if (
      (ts.isMethodDeclaration(n) || ts.isFunctionDeclaration(n) || ts.isGetAccessor(n)) &&
      n.name
    ) {
      return n.name.getText();
    }
  }
  return '?';
}

/**
 * Where the value of a helper call goes: `{ converted: true }`, a field it is
 * the value of (`{ field }`), or a label for anything else (`{ label }`). A
 * `cond ? helper() : other` is followed to where the whole choice goes.
 */
function localInputTarget(call) {
  let node = call;
  let parent = parentOf(node);
  while (parent && ts.isConditionalExpression(parent) && unwrap(parent.condition) !== node) {
    node = parent;
    parent = parentOf(node);
  }
  if (!parent) return { label: call.getText() };
  if (ts.isCallExpression(parent) && parent.expression.getText() === 'toServerDateTime') {
    return { converted: true };
  }
  if (
    (ts.isPropertyAssignment(parent) || ts.isPropertyDeclaration(parent)) &&
    parent.initializer &&
    unwrap(parent.initializer) === node
  ) {
    return { field: parent.name.getText() };
  }
  if (
    ts.isBinaryExpression(parent) &&
    parent.operatorToken.kind === ts.SyntaxKind.EqualsToken &&
    unwrap(parent.right) === node
  ) {
    const left = unwrap(parent.left);
    if (ts.isPropertyAccessExpression(left)) return { field: left.name.text };
    if (ts.isIdentifier(left)) return { field: left.text };
  }
  if (ts.isReturnStatement(parent)) return { label: `return in ${enclosingName(parent)}` };
  return { label: call.getText().replace(/\s+/g, ' ') };
}

/**
 * Every `nowLocalDatetime(...)` / `toLocalInputValue(...)` of a TypeScript
 * source that is neither converted in place nor the default of one of
 * `fields` (the fields its component's `datetime-local` inputs bind), minus
 * the `LOCAL_INPUT_EXEMPT` allowances of `file`.
 *
 * @returns {{ line: number, target: string }[]}
 */
export function localInputValuesOffInputs(source, fields, file = '') {
  if (!/nowLocalDatetime|toLocalInputValue/.test(source)) return [];
  const sf = ts.createSourceFile('x.ts', source, ts.ScriptTarget.Latest, true);
  const found = [];
  const visit = (node) => {
    if (
      ts.isCallExpression(node) &&
      ts.isIdentifier(node.expression) &&
      LOCAL_INPUT_HELPERS.has(node.expression.text)
    ) {
      const target = localInputTarget(node);
      const allowed =
        target.converted ||
        (target.field !== undefined && fields.has(target.field)) ||
        LOCAL_INPUT_EXEMPT.has(`${file}:${target.field ?? target.label}`);
      if (!allowed) {
        found.push({
          line: sf.getLineAndCharacterOfPosition(node.getStart()).line + 1,
          target: target.field ?? target.label,
        });
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return found;
}
