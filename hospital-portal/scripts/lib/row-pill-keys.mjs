/**
 * QA D1222 (round 23): the lab worklist's row pill for ONE result reused the
 * filter chip's key, LAB_RESULTS.PENDING_RELEASE, a label for a COUNT of
 * results: in Spanish the row read "Pendientes de publicación" (plural) for
 * a single result, and "Corrección Pendientes de publicación" on a
 * correction. A count label and a row's status are two keys: languages that
 * inflect for number (es, fr) need both forms.
 *
 * Finds every translate key a template uses BOTH as a row's status pill (an
 * element whose class names a badge / pill / status, inside an `@for` row)
 * and as a count label (followed by a count in brackets, `({{ n }})`, or in
 * an element whose class names a card label, a filter, a chip or a tab).
 */
import { balanced } from './js-scan.mjs';

const KEY = String.raw`'([A-Z][A-Z0-9_]*(?:\.[A-Z0-9_]+)+)'\s*\|\s*translate`;
const ROW_PILL = new RegExp(
  String.raw`class="[^"]*\b(?:[\w-]*badge|[\w-]*pill|status-[\w-]+)\b[^"]*"[^>]*>\s*\{\{\s*${KEY}`,
  'g',
);
const COUNT_LABEL = [
  new RegExp(String.raw`\{\{\s*${KEY}\s*\}\}\s*\(\{\{`, 'g'),
  new RegExp(
    String.raw`class="[^"]*\b(?:card-label|[\w-]*filter[\w-]*|[\w-]*chip[\w-]*|[\w-]*tab[\w-]*)\b[^"]*"[^>]*>\s*\{\{\s*${KEY}`,
    'g',
  ),
];

/** The text of every `@for` body of the template. */
function forBodies(html) {
  const out = [];
  for (const m of html.matchAll(/@for\s*\(/g)) {
    const head = balanced(html, m.index + m[0].length - 1, '(', ')');
    const brace = html.indexOf('{', m.index + m[0].length - 1 + head.length);
    if (brace >= 0) out.push(balanced(html, brace, '{', '}'));
  }
  return out;
}

/** Keys used both as a row's status pill and as a count label. */
export function sharedRowAndCountKeys(html) {
  const row = new Set();
  for (const body of forBodies(html)) for (const m of body.matchAll(ROW_PILL)) row.add(m[1]);
  const count = new Set();
  for (const re of COUNT_LABEL) for (const m of html.matchAll(re)) count.add(m[1]);
  return [...row].filter((k) => count.has(k)).sort();
}
