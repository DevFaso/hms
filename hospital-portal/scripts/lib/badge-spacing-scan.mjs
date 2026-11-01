/**
 * QA D1205 / D1167 / D1137 (rounds 20–22): two status words glued together —
 * "RectificationEn attente de publication", "PubliéCorrigé", "12,8 g/dLCorrigé".
 *
 * Angular drops a whitespace-only text node between two elements, so a badge
 * written on its own line after another inline element is glued to it in the
 * text a screen reader speaks and a copy pastes. A `&ngsp;` written right
 * after the `}` that closes a block is dropped too (shown by the lab-results
 * spec in round 22: the pending correction row read glued with one there).
 *
 * `gluedBefore(html, index)`: is the badge opening at `index` glued to an
 * inline neighbour before it? The neighbour is the inline element or the
 * interpolation that ends right before it — directly, or as the last thing of
 * an `@if` / `@else` block that ends right before it. Whitespace and comments
 * are stepped over (they render nothing). A `{{ ' ' }}`, or a `&ngsp;` that
 * does not follow a block's `}`, separates; so does whitespace after an
 * interpolation (it is kept inside that text node).
 */

/** Inline elements whose text runs on into the next one. */
const INLINE_CLOSE = /<\/(?:span|strong|small|em|b|i|mark|code|a|abbr|time|label|app-[\w-]+)\s*>$/;

/** A badge-like element: its class names a status pill. */
export const BADGE_OPEN =
  /<(?:span|strong|small|em|b|mark|a)\b[^>]*?\bclass="(?=[^"]*\b(?:status-badge|badge|chip|pill)\b)[^"]*"/g;

function stripTrailingComments(text) {
  let t = text;
  for (;;) {
    const trimmed = t.trimEnd();
    if (!trimmed.endsWith('-->')) return trimmed;
    t = trimmed.slice(0, trimmed.lastIndexOf('<!--'));
  }
}

/** The body of the `{ … }` block that `text` ends with (text ends with `}`), or null. */
function lastBlockBody(text) {
  let depth = 0;
  for (let i = text.length - 1; i >= 0; i--) {
    if (text[i] === '}') depth++;
    else if (text[i] === '{' && --depth === 0) {
      const before = text.slice(0, i);
      if (!/(?:@if\s*\([^{}]*\)|@else(?:\s+if\s*\([^{}]*\))?|@for\s*\([^{}]*\))\s*$/.test(before)) {
        return null;
      }
      return text.slice(i + 1, text.length - 1);
    }
  }
  return null;
}

/** Whether `text` (what precedes the badge) ends with an inline neighbour the badge runs into. */
function endsInline(text, depth = 0) {
  if (depth > 4) return false;
  // A space written as an interpolation always renders.
  if (/\{\{\s*' '\s*\}\}\s*$/.test(text)) return false;
  // An interpolation glued directly to the badge (`}}<span`); with whitespace between, it is kept.
  if (/\}\}$/.test(text)) return true;
  const t = stripTrailingComments(text);
  if (t === '') return false;
  if (/\{\{\s*' '\s*\}\}$/.test(t)) return false;
  if (t.endsWith('&ngsp;')) {
    // Dropped when it directly follows a block's `}`.
    const before = stripTrailingComments(t.slice(0, -'&ngsp;'.length));
    return before.endsWith('}') && !before.endsWith('}}') && endsInline(before, depth + 1);
  }
  if (/\}\}$/.test(t)) return false;
  if (t.endsWith('}')) {
    const body = lastBlockBody(t);
    return body !== null && endsInline(body, depth + 1);
  }
  // A block head right before the badge: the badge is the block's first child;
  // what came before the block decides.
  const head = /(?:@if\s*\([^{}]*\)|@else(?:\s+if\s*\([^{}]*\))?)\s*\{$/.exec(t);
  if (head) {
    let before = t.slice(0, head.index);
    if (head[0].startsWith('@else')) {
      // `} @else {`: the branch before never renders with this one.
      const trimmed = stripTrailingComments(before);
      const body = trimmed.endsWith('}') ? lastBlockBody(trimmed) : null;
      if (body === null) return false;
      before = trimmed.slice(0, trimmed.length - body.length - 2);
      before = before.replace(/(?:@if\s*\([^{}]*\)|@else(?:\s+if\s*\([^{}]*\))?)\s*$/, '');
    }
    return endsInline(before, depth + 1);
  }
  return INLINE_CLOSE.test(t);
}

/** Whether the badge opening at `index` of `html` is glued to an inline neighbour before it. */
export function gluedBefore(html, index) {
  return endsInline(html.slice(0, index));
}

/** Every glued badge of a template: `{ line, tag }`. */
export function gluedBadges(html) {
  const out = [];
  for (const m of html.matchAll(BADGE_OPEN)) {
    if (gluedBefore(html, m.index)) {
      out.push({ line: html.slice(0, m.index).split('\n').length, tag: m[0].slice(0, 80) });
    }
  }
  return out;
}
