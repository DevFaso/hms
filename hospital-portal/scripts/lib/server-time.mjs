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
 * profile's last login were off by the reader's offset). A `new Date` whose
 * argument is a field named like an instant (`...At`, `...Time`,
 * `...DateTime`, `...timestamp`) must be `serverInstant(...)` from
 * shared/date-utils. Fields named `...Date` are left alone: most are calendar
 * dates, which `serverInstant` would leave as they are anyway.
 *
 * @returns {string[]} the argument of every such `new Date(...)`
 */
export function bareServerParses(source) {
  const found = [];
  for (const m of source.matchAll(/new Date\(([^()]+)\)/g)) {
    const arg = m[1].trim();
    if (/(At|Time|DateTime|Datetime|[tT]imestamp)!?$/.test(arg)) found.push(arg);
  }
  return found;
}
