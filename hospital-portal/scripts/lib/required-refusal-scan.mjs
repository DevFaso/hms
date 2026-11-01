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
 * `instant('…MISSING…')`, `instant('…VALIDATION_ERROR…')` — and does not call
 * `refuseFields(` or `focusFirstInvalid(` in the same method body. The anchor
 * is `method::KEY`, so a pin survives the lines around it moving.
 *
 * A reason the server sent back is not this class (the field comes back in
 * `fieldErrors` and is shown by the template), and neither is a confirmation
 * dialog's own reason box; those are pinned with their reason.
 */
import { balanced, endOfLiteralOrComment } from './js-scan.mjs';
import { lineOf } from './html-tags.mjs';

const REFUSAL =
  /\.instant\(\s*'([A-Z0-9_]+(?:\.[A-Z0-9_]+)*\.[A-Z0-9_]*(?:REQUIRED|MISSING|VALIDATION_ERROR)[A-Z0-9_]*)'\s*\)/g;
const HELPERS = ['refuseFields(', 'focusFirstInvalid('];
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
    // Skip a return type up to the body's `{` (never past a `;`, which ends a declaration).
    while (i < source.length && source[i] !== '{' && source[i] !== ';') {
      const skipped = endOfLiteralOrComment(source, i);
      i = skipped >= 0 ? skipped + 1 : i + 1;
    }
    if (source[i] !== '{') continue;
    const body = balanced(source, i, '{', '}');
    out.push({ name: m[1], start: i, end: i + body.length });
  }
  return out;
}

/**
 * @param {string} source a component's `.ts`
 * @returns {{line:number, rule:string, anchor:string, message:string}[]}
 */
export function requiredRefusalFindings(source) {
  if (!REFUSAL.test(source)) return [];
  REFUSAL.lastIndex = 0;
  const bodies = members(source);
  const out = [];
  for (const m of source.matchAll(REFUSAL)) {
    // The innermost member around the call (a nested arrow belongs to its method).
    const owner = bodies
      .filter((b) => b.start <= m.index && m.index < b.end)
      .sort((a, b) => a.end - a.start - (b.end - b.start))[0];
    if (!owner) continue;
    const body = source.slice(owner.start, owner.end);
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
    out.push({
      line: lineOf(source, m.index),
      rule: 'unmarked-refusal',
      anchor: `${owner.name}::${m[1]}`,
      message: `${owner.name}() refuses with ${m[1]} but marks no field: call refuseFields()/focusFirstInvalid() (shared/forms/invalid-fields)`,
    });
  }
  return out;
}
