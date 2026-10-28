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
 *  toast   — `toast.success|error|info|warning(` whose first argument is a
 *            string literal (quoted or template) that is prose: it contains
 *            a space and two words of letters, and is not a translation key
 *            (`SECTION.KEY_NAME`).
 *  signal  — `.set(` or `= ` on a field named error*, message*, notice*,
 *            hint*, banner*, warning*, statusMessage*, info* with such a
 *            literal.
 *
 * Every toast in the tree already goes through translate.instant; the only
 * sites today are four English error strings in reset-password.ts, pinned in
 * scripts/ts-literals-baseline.json until they are keyed. A fifth fails.
 */
import { lineOf } from './html-tags.mjs';

const PROSE_WORDS = /[A-Za-zÀ-ÿ]{2,}\s+[A-Za-zÀ-ÿ]{2,}/;
const TRANSLATION_KEY = /^[A-Z0-9_]+(?:\.[A-Z0-9_]+)+$/;

const LITERAL = String.raw`(?:'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"|` + '`(?:[^`\\\\]|\\\\.)*`' + ')';
// `toast.error('…')`, and `toast.push('error', '…')` whose message is the SECOND argument.
const TOAST_CALL = new RegExp(
  String.raw`\btoast\w*\.(?:(success|error|info|warning)\(\s*|(push)\(\s*['"]\w+['"]\s*,\s*)${LITERAL}`,
  'g',
);
// `this.error.set('…')`, `this.error = '…'`, `error: string = '…'`, `readonly notice = signal('…')`.
const SIGNAL_SET = new RegExp(
  String.raw`\b((?:error|message|notice|hint|banner|warning|statusMessage|info)\w*)` +
    String.raw`(?:\.set\(|\s*(?::\s*[\w<>|\[\]\s]+?)?\s*=\s*(?:signal(?:<[^>]*>)?\()?)\s*${LITERAL}`,
  'g',
);

/** The TRAILING literal of the match: `push('error', 'Could not …')` yields the message, not the type. */
function literalOf(call) {
  const m = /(['"`])((?:\\.|(?!\1)[^\\])*)\1\s*$/.exec(call);
  return m ? m[2] : '';
}

export function isProseLiteral(text) {
  const plain = text.replace(/\$\{[^}]*\}/g, ' ').trim();
  if (TRANSLATION_KEY.test(plain)) return false;
  return PROSE_WORDS.test(plain);
}

const lineAt = lineOf;

/**
 * @param {string} source a non-spec `.ts` file
 * @returns {{ line: number, rule: string, anchor: string, message: string }[]}
 */
export function tsLiteralFindings(source) {
  const out = [];
  for (const m of source.matchAll(TOAST_CALL)) {
    const literal = literalOf(m[0]);
    if (!isProseLiteral(literal)) continue;
    out.push({
      line: lineAt(source, m.index),
      rule: 'toast',
      anchor: literal.slice(0, 60),
      message: `toast.${m[1] ?? m[2]}('${literal.slice(0, 60)}'): English on screen in every language — translate.instant('KEY')`,
    });
  }
  for (const m of source.matchAll(SIGNAL_SET)) {
    const literal = literalOf(m[0]);
    if (!isProseLiteral(literal)) continue;
    out.push({
      line: lineAt(source, m.index),
      rule: 'signal',
      anchor: `${m[1]} ${literal.slice(0, 60)}`,
      message: `${m[1]} set to '${literal.slice(0, 60)}': English on screen in every language — translate.instant('KEY')`,
    });
  }
  return out;
}
