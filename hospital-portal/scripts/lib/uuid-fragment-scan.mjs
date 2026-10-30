/**
 * Raw identifier fragments on screen (QA D992; D1009 at the file-name level).
 *
 * A UUID's first eight characters are nothing a clinician can read or say back
 * ("Visite 3f9a2c1e"). Every entity that people name has a human code
 * (encounterNumber, MRN, invoice number, LAB-000005); a template that shows
 * `encounter.id | slice: 0 : 8` is a placeholder someone forgot to replace.
 * Two rules:
 *
 *  slice-pipe  — `| slice: 0 : N` (N ≤ 12) applied to an id-shaped expression
 *                (…id, …Id, …uuid, …Uuid, …Ref, …reference, idValue…) in a template.
 *  slice-call  — `.slice(0, N)`, `.substring(0, N)`, `.substr(0, N)` (N ≤ 12)
 *                on an id-shaped expression in a template or a component,
 *                through `?.` and `!.` as well (`row.id?.slice(0, 8)`).
 *  strip-slice — QA D1032: the dashes stripped first, then a slice
 *                (`.replace(/-/g, '').substring(0, 10)`, `.replaceAll('-', '')
 *                .slice(0, 8)`, `.split('-').join('').substr(0, 12)`): the
 *                pharmacy receipt printed "Nº 38D0FCFE16" and the id-shaped
 *                test above never saw it, because the receiver was a bare
 *                `(id ?? '')` and the slice came after the strip. Whatever the
 *                receiver, a dash-stripped string cut to 32 characters or
 *                fewer is a UUID fragment.
 */
import { lineOf } from './html-tags.mjs';

const ID_EXPR = String.raw`([\w$.?!()\[\]]*?(?:\w*(?:[iI]d|[uU]uid|Ref|[rR]eference)|\b(?:id|uuid)[A-Z]\w*)(?:\(\))?)`;
// The first N characters (`slice: 0 : 8`) or the LAST N (`slice: -8`,
// `.slice(-8)`, `.substring(id.length - 8)`): a UUID tail is as unreadable as its head.
const SLICE_PIPE = new RegExp(
  String.raw`${ID_EXPR}\)?\s*[?!]?\s*\|\s*slice\s*:\s*(?:0\s*:\s*(\d{1,2})|-(\d{1,2}))\b`,
  'g',
);
const SLICE_CALL = new RegExp(
  String.raw`${ID_EXPR}\)?\s*[?!]?\s*\.(slice|substring|substr)\(\s*(?:0\s*,\s*(\d{1,2})|-(\d{1,2})|[\w$.?!]+\.length\s*-\s*(\d{1,2}))\s*\)`,
  'g',
);
// `id.split('-')[0]`: the first UUID group, eight characters, the same fragment.
const SPLIT_CALL = new RegExp(
  String.raw`${ID_EXPR}\)?\s*[?!]?\s*\.split\(\s*(['"])-\2\s*\)\s*[?!]?\s*\[\s*0\s*\]`,
  'g',
);
const MAX_FRAGMENT = 12;
// QA D1032: dashes stripped, then cut: `.replace(/-/g, '')`, `.replaceAll('-', '')`,
// `.split('-').join('')`, optionally followed by a case change, then a slice of 32 or fewer.
const STRIP_SLICE = new RegExp(
  String.raw`\.(?:replace\(\s*\/-\/g\s*,|replaceAll\(\s*(['"])-\1\s*,|split\(\s*(['"])-\2\s*\)\s*\.join\()\s*(['"])\3\s*\)` +
    String.raw`(?:\s*\.to(?:Upper|Lower)Case\(\))?\s*\.(slice|substring|substr)\(\s*0\s*,\s*(\d{1,2})\s*\)`,
  'g',
);
const UUID_HEX_LENGTH = 32;

/** The fragment length a match asks for, whichever spelling it used. */
function fragmentLength(...groups) {
  return Number(groups.find((g) => g !== undefined));
}

/**
 * Whole words that end in "id" but are not identifiers. Compared against the
 * LAST identifier of the expression exactly, never as a tail: a tail match of
 * `rid`/`did`/`lid` once excused `encounterId`, `userId` and `hospitalId`.
 */
const NOT_AN_ID = new Set([
  'valid',
  'invalid',
  'paid',
  'unpaid',
  'void',
  'rapid',
  'fluid',
  'acid',
  'grid',
  'mid',
  'hybrid',
  'solid',
  'liquid',
  'avoid',
  'bid',
  'lid',
  'kid',
  'hid',
  'did',
  'rid',
  'said',
  'aid',
  'raid',
  'android',
]);

const lineAt = lineOf;

function isId(expr) {
  const last = expr
    .replace(/[!?]/g, '')
    .split('.')
    .pop()
    .replace(/[()[\]]/g, '');
  // The last camelCase word decides: `isValid` → "valid" (not an id),
  // `hospitalId` → "id" (an id), `unpaid` → "unpaid" (not an id).
  const lastWord = last.split(/(?=[A-Z])/).pop();
  return !NOT_AN_ID.has(lastWord.toLowerCase());
}

/**
 * @param {string} text a template or a component source
 * @returns {{ line: number, rule: string, anchor: string, message: string }[]}
 */
export function uuidFragmentFindings(text) {
  const out = [];
  for (const m of text.matchAll(SLICE_PIPE)) {
    const n = fragmentLength(m[2], m[3]);
    if (!isId(m[1]) || n > MAX_FRAGMENT) continue;
    out.push({
      line: lineAt(text, m.index),
      rule: 'slice-pipe',
      anchor: `${m[1]} | slice`,
      message: `${m[1]} | slice (${n} characters): a UUID fragment on screen — show the human code (number, MRN, reference)`,
    });
  }
  for (const m of text.matchAll(SLICE_CALL)) {
    const n = fragmentLength(m[3], m[4], m[5]);
    if (!isId(m[1]) || n > MAX_FRAGMENT) continue;
    out.push({
      line: lineAt(text, m.index),
      rule: 'slice-call',
      anchor: `${m[1]}.${m[2]}`,
      message: `${m[1]}.${m[2]}(…) (${n} characters): a UUID fragment on screen — show the human code (number, MRN, reference)`,
    });
  }
  for (const m of text.matchAll(STRIP_SLICE)) {
    const n = Number(m[5]);
    if (n > UUID_HEX_LENGTH) continue;
    out.push({
      line: lineAt(text, m.index),
      rule: 'strip-slice',
      anchor: `${m[4]}(0, ${n}) after stripping dashes`,
      message: `dashes stripped then .${m[4]}(0, ${n}): a UUID fragment on screen — show the human code (number, receipt number, reference)`,
    });
  }
  for (const m of text.matchAll(SPLIT_CALL)) {
    if (!isId(m[1])) continue;
    out.push({
      line: lineAt(text, m.index),
      rule: 'slice-call',
      anchor: `${m[1]}.split`,
      message: `${m[1]}.split('-')[0]: the first UUID group on screen — show the human code (number, MRN, reference)`,
    });
  }
  return out;
}
