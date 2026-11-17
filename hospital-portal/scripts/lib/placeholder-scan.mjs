/**
 * QA D837 / D867: the placeholder and help-text checks behind
 * check-i18n-placeholders.mjs, as pure functions so the gate's own tests can
 * hold each rule to the exact text QA reported.
 *
 * Every other i18n gate asks about KEYS. These defects were not missing keys:
 *
 *  - a template wrote its example as a literal (`placeholder="e.g. 718-7"`,
 *    `placeholder="+1 (555) 000-0000"`), so no key existed to translate;
 *  - a French value kept the English example (`ex. : TABLET` for `e.g. TABLET`)
 *    or a US phone format (`ex. (555) 123-4567`), which differs from English
 *    by its marker and so passed the byte-identical translation gate;
 *  - help text named a Java class (`TenantProvisioningClient`), which is
 *    present and translated around, and means nothing to a hospital admin.
 */

/** An example marker at the start of a value, in any of the three languages. */
const EXAMPLE_MARKER =
  /^\s*(?:e\.\s?g\.|eg\b|ex\.|ej\.|p\.\s?ej\.|par ex(?:emple)?\.?|por ejemplo|for example)\s*,?\s*:?\s*/i;

/** A North American number: (555) 123-4567, 555-1234, +1 (…), +1 555 … */
const US_PHONE = /\(\d{3}\)\s?\d{3}[-.\s]\d{4}|\b555[-.\s]\d{4}\b|\+1[\s-]?\(?\d{3}\)?[\s-]\d{3}/;

/** A Java type name: two or more capitalised humps ending in a framework suffix. */
const JAVA_TYPE =
  /\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+(?:Client|Service|ServiceImpl|Exception|Repository|Controller|Impl|Dto|DTO|Handler|Resolver|Factory|Interceptor|Filter|Listener|Bean)\b/;

/** The English marker, which FR and ES must not use. */
const ENGLISH_MARKER = /(^|[\s(])e\.\s?g\.(?=[\s,:]|$)/i;

/** A static `placeholder="…"` attribute: not `[placeholder]`, not `attr.placeholder`. */
const STATIC_PLACEHOLDER = /(^|[\s<])placeholder\s*=\s*"([^"]*)"/g;

/** The example a value gives, its marker removed. */
export function exampleOf(value) {
  return String(value).replace(EXAMPLE_MARKER, '').trim();
}

export function hasExampleMarker(value) {
  return EXAMPLE_MARKER.test(String(value));
}

export function hasUsPhone(value) {
  return US_PHONE.test(String(value));
}

export function javaTypeIn(value) {
  return String(value).match(JAVA_TYPE)?.[0] ?? null;
}

/**
 * Literal placeholders in one template that should be a translated key: an
 * example (`e.g. 718-7`, `ex. 500`) or a US phone format. An interpolated one
 * (`placeholder="{{ 'X' | translate }}"`) is a key and is not reported; a bare
 * technical sample (`https://`, `mg`, `ORG-001`) is the same in every language
 * and is left to the reviewer.
 *
 * @returns {{ line: number, value: string, reason: string }[]}
 */
export function literalPlaceholders(templateText) {
  const found = [];
  for (const m of templateText.matchAll(STATIC_PLACEHOLDER)) {
    const value = m[2];
    if (value.includes('{{')) continue;
    let reason = null;
    if (hasExampleMarker(value)) reason = 'a written-out example';
    else if (hasUsPhone(value)) reason = 'a US phone format';
    if (!reason) continue;
    const line = templateText.slice(0, m.index + m[1].length).split('\n').length;
    found.push({ line, value, reason });
  }
  return found;
}

/**
 * Locale values that read wrong to a French or Spanish user.
 *
 * @param {string} locale    'en', 'fr' or 'es'
 * @param {Map<string,string>} values  this locale, flattened
 * @param {Map<string,string>} english en.json, flattened
 * @param {Record<string,string>} pinned  key → the exact value accepted for it
 * @returns {{ key: string, value: string, reason: string }[]}
 */
export function localeProblems(locale, values, english, pinned = {}) {
  const found = [];
  for (const [key, value] of values) {
    if (pinned[key] === value) continue;
    const type = javaTypeIn(value);
    if (type) {
      found.push({ key, value, reason: `names the Java type ${type}` });
      continue;
    }
    if (hasUsPhone(value)) {
      found.push({ key, value, reason: 'a US phone format' });
      continue;
    }
    if (locale === 'en') continue;
    if (ENGLISH_MARKER.test(value)) {
      found.push({ key, value, reason: 'the English marker "e.g."' });
      continue;
    }
    const en = english.get(key);
    if (en && en !== value && hasExampleMarker(en) && hasExampleMarker(value)) {
      const example = exampleOf(value);
      // The English example word kept as is: `ex. : TABLET` for `e.g. TABLET`.
      // A code (digits, `-`, `_`, `.`) or a proper name in mixed case is the
      // same in every language; a bare capitalised word is English shown as a
      // wire code, and needs a French or Spanish word — or a pin.
      if (/^[A-Z]{3,}$/.test(example) && example.toLowerCase() === exampleOf(en).toLowerCase()) {
        found.push({ key, value, reason: `keeps the English example "${example}"` });
      }
    }
  }
  return found;
}
