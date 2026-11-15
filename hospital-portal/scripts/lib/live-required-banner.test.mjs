// QA D1940 (round 54) guard: a "Champs obligatoires manquants : …" banner that
// a form sets on submit kept naming fields after they were filled, until the
// next submit (the booking form, the reschedule dialog).
//
// A banner that stays on screen is a signal. Setting a signal from
// missingRequiredMessage() freezes the sentence at the submit; the form goes
// through LiveRequiredMessage (shared/required-fields.ts) instead, which keeps
// it current as the reader types. A toast (it goes by itself) may still take
// missingRequiredMessage() directly. The submit guard's own reason under the
// button follows the edits in SubmitGuardDirective.
//
// Flagged: `x.set(missingRequiredMessage(…))` (over line breaks too), and
// `const m = missingRequiredMessage(…)` followed in the next lines by `x.set(m)`.
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { componentSources } from './component-sources.mjs';

const DIRECT = /\.set\(\s*missingRequiredMessage\(/g;
const ASSIGNED = /\bconst\s+(\w+)\s*=\s*missingRequiredMessage\(/g;

const lineOf = (text, index) => text.slice(0, index).split('\n').length;

export function frozenRequiredBanners(text) {
  const hits = [];
  for (const m of text.matchAll(DIRECT)) hits.push(lineOf(text, m.index));
  for (const m of text.matchAll(ASSIGNED)) {
    const after = text.slice(m.index).split('\n').slice(1, 25).join('\n');
    const set = new RegExp(String.raw`\.set\(\s*${m[1]}\s*\)`);
    if (set.test(after)) hits.push(lineOf(text, m.index));
  }
  return hits.sort((a, b) => a - b);
}

test('a "missing fields" banner kept on screen follows the edits (LiveRequiredMessage)', () => {
  const offenders = [];
  for (const { file, text } of componentSources()) {
    // The helper itself sets the banner it keeps current.
    if (file.endsWith('shared/required-fields.ts')) continue;
    for (const line of frozenRequiredBanners(text)) offenders.push(`${file}:${line}`);
  }
  assert.deepEqual(
    offenders,
    [],
    'a banner set from missingRequiredMessage() names filled fields until the next submit (QA D1940): ' +
      'use LiveRequiredMessage.check() from shared/required-fields.ts',
  );
});

test('the scan catches the D1940 shape and accepts its fix', () => {
  const assigned = [
    '    const missing = missingRequiredMessage(this.translate, fields);',
    '    if (missing) {',
    '      this.formError.set(missing);',
  ].join('\n');
  assert.deepEqual(frozenRequiredBanners(assigned), [1]);
  assert.deepEqual(
    frozenRequiredBanners(
      '      this.bookingError.set(\n        missingRequiredMessage(this.translate, [',
    ),
    [1],
  );
  assert.deepEqual(frozenRequiredBanners('this.toast.error(missingRequiredMessage(t, f));'), []);
  assert.deepEqual(
    frozenRequiredBanners('const m = missingRequiredMessage(t, f);\nthis.toast.error(m);'),
    [],
  );
  assert.deepEqual(
    frozenRequiredBanners('if (this.formMissing.check(this.host.nativeElement)) {'),
    [],
  );
});
