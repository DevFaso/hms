/**
 * Translation calls that leave a placeholder unfilled (QA D1035).
 *
 * "{{count}} créneaux générés" reached the screen because the page passed
 * `{ created, templates, skipped }` to a value that names `{{count}}`. No
 * other gate compares the parameters a call hands over with the placeholders
 * the value names: i18n:parity checks the locales agree with each other, not
 * with the code.
 *
 * Two call shapes, with a literal key and an object-literal argument:
 *   'KEY' | translate: { a: x, b: y }          (template)
 *   translate.instant('KEY', { a: x, b: y })   (component; also .get/.stream)
 * A placeholder of the key's English value that is not among the argument's
 * property names is a finding. A call whose argument is not an object literal
 * (a variable, a spread) cannot be read and is skipped. A call with no
 * argument at all is a finding for every placeholder its value names.
 *
 * `valueOf` hands back the key's values in EVERY locale (joined), so a
 * placeholder only the French or Spanish value names is checked too: a
 * translation that renamed {{created}} to {{count}} shows the raw token in
 * that language alone, which an English-only read could not see.
 */
import { lineOf } from './html-tags.mjs';

const PIPE_CALL = /'([A-Z0-9_]+(?:\.[A-Z0-9_]+)+)'\s*\|\s*translate\s*:\s*\{/g;
const TS_CALL = /\.(?:instant|get|stream)\(\s*'([A-Z0-9_]+(?:\.[A-Z0-9_]+)+)'\s*,\s*\{/g;
const PLACEHOLDER = /\{\{\s*(\w+)\s*\}\}/g;
// Round 18 (D1035 hunt): a call that passes NO argument at all leaves every
// placeholder of its value raw, and the first scan only read calls WITH an
// object literal. `'KEY' | translate` not followed by `:`, and
// `.instant('KEY')` / `.get('KEY')` / `.stream('KEY')` closed right after the key.
const PIPE_BARE = /'([A-Z0-9_]+(?:\.[A-Z0-9_]+)+)'\s*\|\s*translate\b(?!\s*:)/g;
const TS_BARE = /\.(?:instant|get|stream)\(\s*'([A-Z0-9_]+(?:\.[A-Z0-9_]+)+)'\s*\)/g;

/** The text of the object literal whose `{` is at `open`, or null when unbalanced. */
function objectAt(text, open) {
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return text.slice(open + 1, i);
    }
  }
  return null;
}

/** The top-level property names of an object literal body (`a: x`, shorthand `a`). */
function propertyNames(body) {
  const names = new Set();
  let depth = 0;
  let current = '';
  const flush = () => {
    const m = /^\s*(?:'(\w+)'|"(\w+)"|(\w+))\s*(?::|$)/.exec(current);
    if (m) names.add(m[1] ?? m[2] ?? m[3]);
    current = '';
  };
  const code = body.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
  for (const c of code) {
    if ('([{'.includes(c)) depth++;
    if (')]}'.includes(c)) depth--;
    if (c === ',' && depth === 0) flush();
    else current += c;
  }
  flush();
  return names;
}

/**
 * @param {string} text a template or component source
 * @param {(key: string) => string | undefined} valueOf the English value of a key
 */
export function translateParamFindings(text, valueOf) {
  const out = [];
  for (const re of [PIPE_CALL, TS_CALL]) {
    for (const m of text.matchAll(re)) {
      const key = m[1];
      const value = valueOf(key);
      if (typeof value !== 'string') continue;
      const body = objectAt(text, m.index + m[0].length - 1);
      if (body === null || body.includes('...')) continue;
      const given = propertyNames(body);
      for (const p of value.matchAll(PLACEHOLDER)) {
        if (given.has(p[1])) continue;
        out.push({
          line: lineOf(text, m.index),
          rule: 'missing-param',
          anchor: `${key}{{${p[1]}}}`,
          message: `${key} names {{${p[1]}}} but the call passes { ${[...given].join(', ')} }: the reader sees the raw placeholder`,
        });
      }
    }
  }
  for (const re of [PIPE_BARE, TS_BARE]) {
    for (const m of text.matchAll(re)) {
      const key = m[1];
      const value = valueOf(key);
      if (typeof value !== 'string') continue;
      const names = [...new Set([...value.matchAll(PLACEHOLDER)].map((p) => p[1]))];
      for (const name of names) {
        out.push({
          line: lineOf(text, m.index),
          rule: 'no-params',
          anchor: `${key}{{${name}}}`,
          message: `${key} names {{${name}}} but the call passes no parameters: the reader sees the raw placeholder`,
        });
      }
    }
  }
  return out;
}

/** Flattens a locale JSON into "A.B.C" -> value. */
export function flatten(obj, prefix = '', out = new Map()) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === 'object') flatten(v, key, out);
    else out.set(key, v);
  }
  return out;
}

/**
 * Round 18 (D1035 hunt): keys whose placeholders differ between locales. A
 * call that passes the English names leaves a renamed French or Spanish
 * placeholder raw in that language only.
 *
 * @param {Map<string, Map<string, unknown>>} byLocale flattened locales by code; the first is the reference
 * @returns {string[]} "locale KEY en={a,b} fr={c}" lines
 */
export function placeholderMismatches(byLocale) {
  const names = (v) =>
    typeof v === 'string'
      ? [...new Set([...v.matchAll(PLACEHOLDER)].map((p) => p[1]))].sort().join(',')
      : null;
  const [[refLocale, ref], ...others] = [...byLocale.entries()];
  const out = [];
  for (const [key, value] of ref) {
    const want = names(value);
    for (const [locale, map] of others) {
      if (!map.has(key)) continue;
      const got = names(map.get(key));
      if (got !== want) out.push(`${locale} ${key} ${refLocale}={${want}} ${locale}={${got}}`);
    }
  }
  return out;
}
