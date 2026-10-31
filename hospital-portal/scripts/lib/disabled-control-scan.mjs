/**
 * QA D1124 / D1089 (NOT_FIXED in rounds 18, 19 and 20): a submit-like control
 * that is disabled for a reason the reader is never told.
 *
 * Round 19 fixed the money buttons ("Payer", the invoice line forms) and its
 * guard looked only at amount-shaped names, so round 20 still found ~20 dead
 * buttons on every staff area and the portal: prescription "Créer"
 * (`!form.medicationName`), "Dispenser" (`form.quantityDispensed <= 0 ||
 * !verifyAttested()`), the lab void / reject / collect reasons, the MAR
 * reasons, check-in (`!canSubmit`), new invoice (`!form.patientId`) …
 *
 * The rule now covers every way a control can be switched off and every
 * reason: a control may be disabled only while a request is in flight
 * (`saving()`, `busy`, `actingOnId() === row.id`) or, for a pager, at the
 * first / last page. Everything else stays clickable behind the shared
 * `appSubmitGuard` directive (`shared/forms/submit-guard.directive.ts`),
 * which refuses the click, marks the blank required fields and shows the
 * reason next to the button. A control that must stay disabled carries an
 * `aria-describedby` naming a visible element of the same template that says
 * why.
 *
 * What counts as disabling (on `<button>`, `<a>`, `<input type=submit|button>`,
 * any `role="button"` and any `<fieldset>`): `disabled`, `[disabled]`,
 * `[attr.disabled]`, `aria-disabled` / `[attr.aria-disabled]`,
 * `[class.disabled]` (and `is-disabled`, `btn-disabled`), a `disabled` class
 * token, and an `[ngClass]` / `[class]` binding that names `disabled`.
 */
import { tags } from './html-tags.mjs';

const KEYWORDS = new Set([
  'this',
  'null',
  'undefined',
  'true',
  'false',
  'return',
  'const',
  'let',
  'var',
  'if',
  'else',
  'new',
  'typeof',
  'void',
  'of',
  'in',
  'Math',
]);
/** Loop variables and generic tails that carry no state of their own. */
const NEUTRAL = new Set([
  'id',
  'length',
  'trim',
  'includes',
  'has',
  'row',
  'item',
  'r',
  'm',
  'p',
  'entry',
  'h',
  's',
  'v',
  'doc',
  'member',
  'task',
  'def',
  'recall',
  'order',
  'o',
  'x',
  'it',
  'rx',
  'u',
  'n',
  't',
  'im',
  'med',
  'a',
  'e',
]);
/** Gerunds that name data, not a request in flight. */
const NOT_IN_FLIGHT =
  /^(?:pending|existing|missing|remaining|editing|incoming|outgoing|nothing|something|string|thing|ring|during)/;
const IN_FLIGHT = [
  // `saving`, `uploadingPhoto`, `actingOnId`, `requestingRefill`: a gerund first.
  /^(?:is)?[a-z]+ing(?:[A-Z]\w*)?$/,
  // `addendumSaving`, `reviewBusyId`, `encounterLookupInProgress`, `replayBusyFor`.
  /(?:ing|Busy|busy|InFlight|InProgress)(?:Id|Ids|For|OnId|OnEntryId)?$/,
  // `queueLoadingMore`.
  /(?:Loading|Saving|Sending|Submitting)[A-Z]\w*$/,
];
/** A pager at its first or last page. */
const PAGER =
  /^(?:page|currentPage|pageIndex|[a-z]\w*Page|totalPages|[a-z]\w*TotalPages|hasNext|hasPrev|hasPrevious|[a-z]\w*Has(?:Next|Prev|Previous)|atEarliest\w*|atLatest\w*)$/;

/** Whether one identifier segment names a request in flight. */
export function inFlight(word) {
  return !NOT_IN_FLIGHT.test(word) && IN_FLIGHT.some((re) => re.test(word));
}

/** The expression with its string and template literals and numbers blanked. */
function stripLiterals(expr) {
  return expr
    .replace(/'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"|`(?:[^`\\]|\\.)*`/g, ' ')
    .replace(/\b\d+(?:\.\d+)?\b/g, ' ');
}

/** The body of member `name` in a component's source ('' when absent). */
export function memberBody(ts, name) {
  const header = new RegExp(
    `^ {2}(?:(?:public|private|protected|readonly|get|override)\\s+)*${name}\\s*(?:=\\s*computed\\(\\s*\\(\\)\\s*=>\\s*|\\([^)]*\\)[^{;]*)\\{`,
    'm',
  );
  const m = header.exec(ts);
  if (!m) {
    // `readonly canSubmit = computed(() => a && b);` — an expression body.
    const arrow = new RegExp(
      `^ {2}(?:(?:public|private|protected|readonly)\\s+)*${name}\\s*=\\s*computed\\(\\s*\\(\\)\\s*=>\\s*([^{][\\s\\S]*?)\\);\\s*$`,
      'm',
    ).exec(ts);
    return arrow ? arrow[1] : '';
  }
  let depth = 0;
  for (let i = m.index + m[0].length - 1; i < ts.length; i++) {
    if (ts[i] === '{') depth++;
    else if (ts[i] === '}' && --depth === 0) return ts.slice(m.index + m[0].length, i);
  }
  return '';
}

const CHAIN =
  /[A-Za-z_$][\w$]*(?:\s*(?:\?\.|!\.|\.)\s*[A-Za-z_$][\w$]*|\s*\((?:[^()]|\([^()]*\))*\)|!(?![=]))*/g;

/**
 * The first identifier that makes `expr` more than "a request is in flight"
 * or "a pager is at its end", or `null`. Members of the component that the
 * expression calls or reads (`canSubmit()`, `get canSubmit()`) are followed
 * into their body, two levels deep.
 */
export function silentReason(expr, componentTs = '', depth = 0) {
  for (const chainMatch of stripLiterals(expr).matchAll(CHAIN)) {
    const chain = chainMatch[0];
    const head = chain.replace(/\((?:[^()]|\([^()]*\))*\)/g, '');
    const segments = head.split(/\s*(?:\?\.|!\.|\.)\s*/).map((s) => s.replace(/!$/, ''));
    if (segments.some(inFlight)) continue;
    const named = segments.filter((s) => s && !KEYWORDS.has(s) && !NEUTRAL.has(s));
    if (named.length === 0) continue;
    if (named.every((s) => PAGER.test(s))) continue;
    // `canSubmit()`, `this.canSubmit`, `canSubmit` (a getter): read its body.
    const member = named.length === 1 ? named[0] : null;
    const body = member && depth < 2 ? memberBody(componentTs, member) : '';
    if (body) {
      const inner = silentReason(body, componentTs, depth + 1);
      if (inner === null) continue;
      return `${member} → ${inner}`;
    }
    return named.join('.');
  }
  return null;
}

const CONTROL = new Set(['button', 'a', 'fieldset']);

function isControl(tag) {
  if (CONTROL.has(tag.name)) return true;
  if (tag.name === 'input') return /^(?:submit|button)$/i.test(tag.attrs.get('type') ?? '');
  return tag.attrs.get('role') === 'button';
}

/**
 * Every way the tag is switched off: `{ how, expr }` where `expr` is the
 * bound expression, or `null` for a static attribute or class.
 */
export function disablings(tag) {
  const out = [];
  for (const [key, value] of tag.attrs) {
    if (/^\[(?:attr\.)?(?:disabled|aria-disabled)\]$/.test(key))
      out.push({ how: key, expr: value ?? '' });
    else if (/^\[class\.(?:is-|btn-)?disabled\]$/.test(key))
      out.push({ how: key, expr: value ?? '' });
    else if ((key === '[ngClass]' || key === '[class]') && /disabled/.test(value ?? ''))
      out.push({ how: key, expr: value ?? '' });
    else if (key === 'disabled' || (key === 'aria-disabled' && value !== 'false'))
      out.push({ how: key, expr: null });
    else if (key === 'class' && /(?:^|\s)disabled(?:\s|$)/.test(value ?? ''))
      out.push({ how: 'class="disabled"', expr: null });
  }
  return out;
}

/** The ids declared in a template (`id="x"`), for the "visible reason" exemption. */
function declaredIds(tokens) {
  const ids = new Set();
  for (const t of tokens) {
    const id = t.attrs.get('id');
    if (id) ids.add(id);
  }
  return ids;
}

/** Whether the control points, through `aria-describedby`, at a reason shown in the same template. */
function namesVisibleReason(tag, ids) {
  const described = tag.attrs.get('aria-describedby');
  return !!described && described.split(/\s+/).some((id) => ids.has(id));
}

/**
 * @param {string} html a template
 * @param {string} componentTs the component's `.ts` ('' when none)
 * @returns {{line:number, how:string, expr:string|null, word:string}[]}
 */
export function silentDisabledFindings(html, componentTs = '') {
  const tokens = tags(html);
  const ids = declaredIds(tokens);
  const out = [];
  for (const tag of tokens) {
    if (tag.type !== 'open' || !isControl(tag)) continue;
    for (const { how, expr } of disablings(tag)) {
      if (namesVisibleReason(tag, ids)) continue;
      const word = expr === null ? '(always)' : silentReason(expr, componentTs);
      if (word) out.push({ line: tag.line, how, expr, word });
    }
  }
  return out;
}

/** Every disabling binding of a template (for the "the scan is not blind" count). */
export function countDisablings(html) {
  let n = 0;
  for (const tag of tags(html)) {
    if (tag.type === 'open' && isControl(tag)) n += disablings(tag).length;
  }
  return n;
}
