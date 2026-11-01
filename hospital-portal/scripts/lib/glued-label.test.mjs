// QA D1200 guard (round 22): a value glued to a translated word.
//
// The reception flow board printed "706Attente min." / "731Espera mín.": the
// template wrote `{{ item.waitMinutes }}{{ 'RECEPTION.MIN_WAIT' | translate }}`,
// so the number ran into the label, and the label's word order ("min wait")
// cannot be right in French or Spanish. The same shape sat on the dashboard:
// `{{ n }}{{ 'DASHBOARD.DAYS_SHORT' | translate }}` ("12j") and an English
// plural `'s'` glued to a translated noun ("2 Alerte Critiques").
//
// Rule, over every template of the portal (each `.html` and each inline
// `template:`): two interpolations written back to back (`}}{{`) where one is
// a translated key and the other a value with no text of its own fail — the
// value goes INTO the sentence as a parameter
// (`{{ 'RECEPTION.WAIT_DURATION' | translate: { minutes: n } }}`), where each
// language puts it, with its spaces. A ternary that adds an English plural
// `'s'` fails too. Punctuation keys (COMMON.COLON, …_MARK) and a required-field
// star (`cond ? ' *' : ''`) are not words. Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const PAIR = /\{\{((?:(?!\}\})[\s\S])*?)\}\}\{\{((?:(?!\}\})[\s\S])*?)\}\}/g;
const TRANSLATED = /^\s*'([A-Z0-9_.]+)'\s*\|\s*translate\s*$/;
/** Keys that are punctuation, not words. */
const PUNCTUATION = /(?:COLON|_MARK|COMMA|SEPARATOR|ELLIPSIS)$/;
/** An English plural glued to a word. */
const PLURAL_S = /\?\s*'s'\s*:/;

/** The glued pairs of a template: `{ line, value, key }`. */
export function gluedLabels(html) {
  const out = [];
  for (const m of html.matchAll(PAIR)) {
    const line = html.slice(0, m.index).split('\n').length;
    const [a, b] = [m[1], m[2]];
    if (PLURAL_S.test(a) || PLURAL_S.test(b)) {
      out.push({ line, value: (PLURAL_S.test(a) ? a : b).trim(), key: 'plural s' });
      continue;
    }
    const ta = TRANSLATED.exec(a);
    const tb = TRANSLATED.exec(b);
    if (!ta === !tb) continue;
    const key = (ta ?? tb)[1];
    const value = (ta ? b : a).trim();
    if (PUNCTUATION.test(key)) continue;
    // A value carrying its own text (a star, a separator) is not a bare value.
    if (/['"`]/.test(value)) continue;
    out.push({ line, value, key });
  }
  return out;
}

test('QA D1200: no value is glued to a translated word in any template', () => {
  const found = [];
  let templates = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    templates++;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const g of gluedLabels(text)) {
      found.push(`${rel}:${g.line + (lineOffset ?? 0)} {{ ${g.value} }} + ${g.key}`);
    }
  }
  assert.ok(templates > 200, `every template must be read, saw ${templates}`);
  assert.deepEqual(found, [], "put the value in the sentence: translate: { name: value }");
});

test('QA D1200 guard: the scan sees the flow board, the days and the plural, not punctuation', () => {
  const board = "<div>{{ item.waitMinutes }}{{ 'RECEPTION.MIN_WAIT' | translate }}</div>";
  assert.deepEqual(gluedLabels(board), [
    { line: 1, value: 'item.waitMinutes', key: 'RECEPTION.MIN_WAIT' },
  ]);
  const days = "{{ alert.daysUntilExpiry }}{{ 'DASHBOARD.DAYS_SHORT' | translate }}";
  assert.equal(gluedLabels(days).length, 1);
  const plural =
    "{{ n }} {{ 'DASHBOARD.CRITICAL_ALERT' | translate\n}}{{ n > 1 ? 's' : '' }}";
  assert.equal(gluedLabels(plural)[0].key, 'plural s');
  assert.deepEqual(gluedLabels("{{ 'A.LABEL' | translate }}{{ 'COMMON.COLON' | translate }}"), []);
  assert.deepEqual(gluedLabels("{{ 'A.PHONE' | translate }}{{ required ? ' *' : '' }}"), []);
  assert.deepEqual(gluedLabels("{{ name }}{{ 'COMMON.QUESTION_MARK' | translate }}"), []);
  const fixed = "{{ 'RECEPTION.WAIT_DURATION' | translate: { minutes: item.waitMinutes } }}";
  assert.deepEqual(gluedLabels(fixed), []);
});
