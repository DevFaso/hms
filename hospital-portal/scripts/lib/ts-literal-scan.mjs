/**
 * English literals in component code that reach the screen (QA D940, D977,
 * D1000 at the API; the portal's own layer of the same class — see
 * docs/qa/regression-guards.md "French completeness layers").
 *
 * The locale gates (i18n:parity, i18n:referenced, i18n:translated) see only
 * what goes through ngx-translate. A toast written as
 *
 *     this.toast.error('Could not save the appointment');
 *
 * or a status signal set to a sentence
 *
 *     this.error.set('Something went wrong');
 *
 * is English in every language and visible to no gate. Two rules on `.ts`
 * sources (specs excluded):
 *
 *  toast   — `toast.success|error|info|warning|push(` whose ARGUMENT LIST holds a
 *            string literal (quoted or template) that is prose — anywhere in it,
 *            so `err?.error?.message ?? 'Could not save'` counts — where prose
 *            means a space and two words of letters, and not a translation key
 *            (`SECTION.KEY_NAME`).
 *  signal  — `.set(`, `.update(` or `= ` on a field named error*, message*, notice*,
 *            hint*, banner*, warning*, statusMessage*, info* — or one whose
 *            name ends in Error/Message/Notice/Hint/Banner/Warning (`saveError`,
 *            `formMessage`) — whose expression holds such a literal
 *            (`signal('…')`, `e.message || '…'` included); and a key of that
 *            name in an object handed to `.set(` / `.update(` / `patchState(`
 *            (`state.set({ error: '…' })`).
 *
 * Comments are blanked first, and the fallback of a `translate.instant(…)`
 * call (`instant('KEY') || 'English'`) is not counted: instant returns the key
 * when a translation is missing, so that literal never reaches the screen.
 *
 * Every toast in the tree already goes through translate.instant; the only
 * sites today are five English error strings in reset-password.ts and one
 * `?? 'Already in use'` field-error fallback in user-list.ts, pinned in
 * scripts/ts-literals-baseline.json until they are keyed. A seventh fails.
 */
import { lineOf } from './html-tags.mjs';
import { endOfLiteralOrComment, balanced } from './js-scan.mjs';

const PROSE_WORDS = /[A-Za-zÀ-ÿ]{2,}\s+[A-Za-zÀ-ÿ]{2,}/;
const TRANSLATION_KEY = /^[A-Z0-9_]+(?:\.[A-Z0-9_]+)+$/;

const TOAST_OPEN = /\btoast\w*\.(success|error|info|warning|push)\(/g;
// `this.error.set(`, `this.error = `, `error: string = `, `readonly notice = `
// (not `===`, and not an arrow parameter: `message => '…'` is a function, not an assignment).
// The name may also END in the status word (`saveError`, `formMessage`); a trailing
// `Info` is left out, it names data (`patientInfo = { name: '…' }`).
const STATUS_NAME = String.raw`(?:error|message|notice|hint|banner|warning|statusMessage|info)\w*|[a-z]\w*(?:Error|Message|Notice|Hint|Banner|Warning)\w*`;
const SIGNAL_OPEN = new RegExp(
  String.raw`\b(${STATUS_NAME})(?:\.(?:set|update)\(|\s*(?::\s*[\w<>|[\]\s]+?)?\s*=(?![=>])\s*)`,
  'g',
);
// `state.set({ error: '…' })`, `state.update((s) => ({ ...s, saveError: '…' }))`, `patchState(store, { message: '…' })`
const STATE_CALL = /(?:\.(?:set|update)|\bpatchState)\(/g;
const STATUS_KEY = new RegExp(String.raw`(?:^|[{,\s])(${STATUS_NAME})\s*:\s*(?=['"\x60])`, 'g');
// The receiver of a setter is itself a status field: its whole argument was read by SIGNAL_OPEN.
const STATUS_RECEIVER = new RegExp(String.raw`\b(?:${STATUS_NAME})$`);

/** The balanced `( … )` opened at `openParen`, strings and comments skipped. */
const balancedArguments = (source, openParen) => balanced(source, openParen);

/**
 * The expression after `=`: up to the `;` that ends the statement, outside
 * strings, comments and brackets (a `computed(() => { a; b; })` body is part of
 * the expression), or the bracket that closes the one the assignment sits in.
 */
function assignedExpression(source, from) {
  let depth = 0;
  for (let i = from; i < source.length; i++) {
    const skipped = endOfLiteralOrComment(source, i);
    if (skipped >= 0) {
      i = skipped;
      continue;
    }
    const ch = source[i];
    if (ch === '(' || ch === '[' || ch === '{') depth++;
    else if (ch === ')' || ch === ']' || ch === '}') {
      if (--depth < 0) return source.slice(from, i);
    } else if (ch === ';' && depth === 0) return source.slice(from, i);
  }
  return source.slice(from);
}

/**
 * Whether the literal starting at `at` is the fallback of a `translate.instant(…)`
 * call: `instant('KEY') || 'English'`. ngx-translate's instant returns the key
 * itself when a translation is missing (no custom MissingTranslationHandler in
 * this tree), so that fallback is never on screen.
 */
function isInstantFallback(text, at) {
  const before = text.slice(0, at).trimEnd();
  const op = /(?:\|\||\?\?)$/.exec(before);
  if (!op) return false;
  const call = before.slice(0, before.length - op[0].length).trimEnd();
  if (!call.endsWith(')')) return false;
  let depth = 0;
  for (let i = call.length - 1; i >= 0; i--) {
    if (call[i] === ')') depth++;
    else if (call[i] === '(' && --depth === 0) return /\.instant\s*$/.test(call.slice(0, i));
  }
  return false;
}

/** Every string literal of `text` (comments skipped) that is prose and can reach the screen. */
function proseLiteralsIn(text) {
  const out = [];
  for (let i = 0; i < text.length; i++) {
    const end = endOfLiteralOrComment(text, i);
    if (end < 0) continue;
    if (text[i] !== '/') {
      const literal = text.slice(i + 1, end);
      if (isProseLiteral(literal) && !isInstantFallback(text, i)) out.push(literal);
    }
    i = end;
  }
  return out;
}

export function isProseLiteral(text) {
  const plain = text.replace(/\$\{[^}]*\}/g, ' ').trim();
  if (TRANSLATION_KEY.test(plain)) return false;
  return PROSE_WORDS.test(plain);
}

const lineAt = lineOf;

/**
 * The source with every comment replaced by spaces (newlines kept, so line
 * numbers hold): `// this.error = 'Old text'` is not a call site.
 */
function blankComments(source) {
  let out = '';
  for (let i = 0; i < source.length; i++) {
    const end = endOfLiteralOrComment(source, i);
    if (end < 0) {
      out += source[i];
      continue;
    }
    const piece = source.slice(i, end + 1);
    out += source[i] === '/' ? piece.replace(/[^\n]/g, ' ') : piece;
    i = end;
  }
  return out;
}

/**
 * @param {string} rawSource a non-spec `.ts` file
 * @returns {{ line: number, rule: string, anchor: string, message: string }[]}
 */
export function tsLiteralFindings(rawSource) {
  const source = blankComments(rawSource);
  const out = [];
  for (const m of source.matchAll(TOAST_OPEN)) {
    const args = balancedArguments(source, m.index + m[0].length - 1);
    for (const literal of proseLiteralsIn(args)) {
      out.push({
        line: lineAt(source, m.index),
        rule: 'toast',
        anchor: literal.slice(0, 60),
        message: `toast.${m[1]}('${literal.slice(0, 60)}'): English on screen in every language — translate.instant('KEY')`,
      });
    }
  }
  for (const m of source.matchAll(SIGNAL_OPEN)) {
    const end = m.index + m[0].length;
    const expression = m[0].endsWith('(')
      ? balancedArguments(source, end - 1)
      : assignedExpression(source, end);
    for (const literal of proseLiteralsIn(expression)) {
      out.push({
        line: lineAt(source, m.index),
        rule: 'signal',
        anchor: `${m[1]} ${literal.slice(0, 60)}`,
        message: `${m[1]} set to '${literal.slice(0, 60)}': English on screen in every language — translate.instant('KEY')`,
      });
    }
  }
  // A status key of an object handed to a state setter: `state.set({ error: '…' })`.
  // A key inside a `saveError.set({ … })` argument was read above, so it is skipped.
  for (const m of source.matchAll(STATE_CALL)) {
    if (m[0] !== 'patchState(' && STATUS_RECEIVER.test(source.slice(0, m.index))) {
      continue;
    }
    const args = balancedArguments(source, m.index + m[0].length - 1);
    for (const key of args.matchAll(STATUS_KEY)) {
      const at = key.index + key[0].length;
      const end = endOfLiteralOrComment(args, at);
      const literal = args.slice(at + 1, end);
      if (!isProseLiteral(literal) || isInstantFallback(args, at)) continue;
      out.push({
        line: lineAt(source, m.index + m[0].length - 1 + at),
        rule: 'signal',
        anchor: `${key[1]} ${literal.slice(0, 60)}`,
        message: `${key[1]}: '${literal.slice(0, 60)}' handed to a state setter: English on screen in every language — translate.instant('KEY')`,
      });
    }
  }
  return out;
}
