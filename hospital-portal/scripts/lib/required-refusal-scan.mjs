/**
 * Client-side form refusals that do not point at the field (QA D1072).
 *
 * The registration form refused a missing phone with the toast "Veuillez
 * remplir tous les champs obligatoires" alone: no `aria-invalid`, focus left
 * on the button, the field scrolled out of view (WCAG 3.3.1). The fix is one
 * helper, `shared/forms/invalid-fields.ts` (`refuseFields`, or
 * `focusFirstInvalid` when the template binds its own per-field message).
 *
 * Rule `unmarked-refusal`: a component method that shows a "required /
 * missing / validation" message — `instant('…REQUIRED…')`,
 * `instant('…MISSING…')`, `instant('…VALIDATION_ERROR…')`, and since round 27
 * (QA D1281: the portal's blank question refused with QUESTION_TOO_SHORT)
 * `instant('…TOO_SHORT…')` — and does not call
 * `refuseFields(` or `focusFirstInvalid(` in the same method body. The anchor
 * is `method::KEY`, so a pin survives the lines around it moving.
 *
 * A reason the server sent back is not this class (the field comes back in
 * `fieldErrors` and is shown by the template), and neither is a confirmation
 * dialog's own reason box; those are pinned with their reason.
 *
 * Rule `toast-only-refusal` (QA D1306 / D1302, round 28): the rule above
 * reads the KEY and the METHOD, so it could not see
 * - a refusal whose message is not a REQUIRED-shaped key (the pharmacy
 *   checkout's Mobile Money reference: `PHARMACY_CHECKOUT.REFERENCE_REQUIRED`
 *   was pinned, but its amount refusal goes through `cfaAmountMessageKey(…)`),
 * - an early return in a method that calls `refuseFields` further down (the
 *   patient payment dialog refused a blank amount with a banner and returned
 *   before the reference's `refuseFields`: one helper anywhere in the method
 *   excused every refusal in it).
 * So the unit is now the refusing BLOCK: an `if (…) { … return …; }` (or a
 * one-statement `if (…) return …`) that shows a message — a toast
 * (`toast.error/warning/info`) or a `.set(…)` of translated text — and
 * returns without calling `refuseFields(` / `focusFirstInvalid(` itself is a
 * finding, whatever the key. Its anchor is `method::KEY` (the first
 * translation key the block shows) or `method::if(<condition>)`. A site the
 * first rule already reports is not reported twice.
 */
import { balanced, endOfLiteralOrComment } from './js-scan.mjs';
import { lineOf } from './html-tags.mjs';

const REFUSAL =
  /\.instant\(\s*'([A-Z0-9_]+(?:\.[A-Z0-9_]+)*\.[A-Z0-9_]*(?:REQUIRED|MISSING|VALIDATION_ERROR|TOO_SHORT)[A-Z0-9_]*)'\s*\)/g;
const HELPERS = ['refuseFields(', 'focusFirstInvalid('];
/** A block that shows a message: a toast, or translated text set into a signal. */
const SHOWS = /\.toast\.(?:error|warning|warn|info)\(|\.set\(\s*(?:this\.)?translate\.instant\(/;
const IF = /\bif\s*\(/g;
/**
 * A message about the page, the session or a device, never about a field the
 * reader typed: a load failure, a missing hospital or staff context, an
 * unsupported camera, a record already paid or locked, a success note. Read
 * from the key, so a field refusal can hide behind it only if its own key
 * says it is not one.
 */
const NOT_A_FIELD =
  /(?:LOAD|_ERROR$|NO_HOSPITAL|NEED_HOSPITAL|NO_STAFF|CONTEXT|UNSUPPORTED|NOT_SUPPORTED|FAILED|EXPIRED|NOT_ALLOWED|NO_REGISTRATION|NO_ENCOUNTER|ALREADY|UPDATED|CONFLICT|LOCKED|CANNOT|UNAVAILABLE|NO_EMAIL_ON_FILE|NO_INSURANCE|LIMIT_REACHED|USER_OR_HOSPITAL)/;
// A class member's header at two-space indent: `  name(`, `  private name(`,
// `  async name(`, `  get name(` … followed (after the parameters and an
// optional return type) by its `{`.
const MEMBER =
  /^ {2}(?:(?:public|private|protected|static|async|override|readonly|get|set)\s+)*([A-Za-z_$][\w$]*)\s*(?:<[^>\n]*>)?\(/gm;

/** Every member of a class source: its name and the [start, end) of its body. */
function members(source) {
  const out = [];
  for (const m of source.matchAll(MEMBER)) {
    const paren = m.index + m[0].length - 1;
    const params = balanced(source, paren, '(', ')');
    let i = paren + params.length;
    const header = i;
    // Skip a return type up to the body's `{` (never past a `;`, which ends a declaration).
    while (i < source.length && source[i] !== '{' && source[i] !== ';') {
      const skipped = endOfLiteralOrComment(source, i);
      i = skipped >= 0 ? skipped + 1 : i + 1;
    }
    if (source[i] !== '{') continue;
    const body = balanced(source, i, '{', '}');
    out.push({ name: m[1], start: i, end: i + body.length, returns: source.slice(header, i) });
  }
  return out;
}

/** The block an `if (…)` guards: its braced body, or its one statement. */
function guardedBlock(source, parenIndex) {
  const cond = balanced(source, parenIndex);
  let i = parenIndex + cond.length;
  while (i < source.length && /\s/.test(source[i])) i++;
  if (source[i] === '{') return { cond, start: i, text: balanced(source, i, '{', '}') };
  let end = i;
  while (end < source.length && source[end] !== ';' && source[end] !== '\n') {
    const skipped = endOfLiteralOrComment(source, end);
    end = skipped >= 0 ? skipped + 1 : end + 1;
  }
  return { cond, start: i, text: source.slice(i, end + 1) };
}

/**
 * QA D1306 / D1302: every refusing block that shows a message and returns
 * without marking a field (see the module comment).
 */
function toastOnlyRefusals(source, bodies, reported) {
  const out = [];
  for (const m of source.matchAll(IF)) {
    const block = guardedBlock(source, m.index + m[0].length - 1);
    if (!/\breturn\b/.test(block.text) || !SHOWS.test(block.text)) continue;
    if (HELPERS.some((h) => block.text.includes(h))) continue;
    const owner = bodies
      .filter((b) => b.start <= m.index && m.index < b.end)
      .sort((a, b) => a.end - a.start - (b.end - b.start))[0];
    if (!owner) continue;
    const end = block.start + block.text.length;
    if (reported.some((at) => block.start <= at && at < end)) continue;
    const key = /\.instant\(\s*'([A-Z0-9_.]+)'/.exec(block.text)?.[1];
    if (key && NOT_A_FIELD.test(key)) continue;
    const cond = block.cond.replaceAll(/\s+/g, ' ').slice(1, -1).trim();
    out.push({
      line: lineOf(source, m.index),
      rule: 'toast-only-refusal',
      anchor: `${owner.name}::${key ?? `if(${cond})`}`,
      message: `${owner.name}() refuses (${key ?? cond}) with a message alone: mark the field with refuseFields() in the same block (shared/forms/invalid-fields), or pin why it is not a field`,
    });
  }
  return out;
}

/**
 * @param {string} source a component's `.ts`
 * @returns {{line:number, rule:string, anchor:string, message:string}[]}
 */
export function requiredRefusalFindings(source) {
  const bodies = members(source);
  const out = [];
  const reported = [];
  REFUSAL.lastIndex = 0;
  for (const m of source.matchAll(REFUSAL)) {
    // The innermost member around the call (a nested arrow belongs to its method).
    const owner = bodies
      .filter((b) => b.start <= m.index && m.index < b.end)
      .sort((a, b) => a.end - a.start - (b.end - b.start))[0];
    if (!owner) continue;
    const body = source.slice(owner.start, owner.end);
    // QA D1302 (round 28): a method that BUILDS the refusals (`: FieldRefusal[]`)
    // hands them to its caller's refuseFields, which marks every field.
    if (owner.returns.includes('FieldRefusal')) continue;
    if (HELPERS.some((h) => body.includes(h))) continue;
    // QA D1158 (round 21): a submit guard's reason method RETURNS the text to
    // `[appSubmitGuard]`, which marks the blank fields (refuseFields) itself;
    // nothing is shown from here. Only a returned reason is exempt.
    if (
      /\breturn\s+(?:this\.)?translate$/.test(
        source.slice(Math.max(0, m.index - 40), m.index).trimEnd(),
      )
    ) {
      continue;
    }
    reported.push(m.index);
    out.push({
      line: lineOf(source, m.index),
      rule: 'unmarked-refusal',
      anchor: `${owner.name}::${m[1]}`,
      message: `${owner.name}() refuses with ${m[1]} but marks no field: call refuseFields()/focusFirstInvalid() (shared/forms/invalid-fields)`,
    });
  }
  return [...out, ...toastOnlyRefusals(source, bodies, reported)];
}
