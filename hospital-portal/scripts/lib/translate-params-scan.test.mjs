import { test } from 'node:test';
import assert from 'node:assert/strict';

import { translateParamFindings } from './translate-params-scan.mjs';

const values = new Map([
  ['SLOT_ADMIN.GENERATED', '{{count}} slots generated'],
  ['A.OK', '{{created}} made, {{skipped}} skipped'],
]);
const valueOf = (k) => values.get(k);

test('QA D1035: the slot generation shape is caught, in a template and in a component', () => {
  const html = "{{ 'SLOT_ADMIN.GENERATED' | translate: { created: r.a, templates: r.b, skipped: r.c } }}";
  const ts = "this.translate.instant('SLOT_ADMIN.GENERATED', {\n  created: r.a,\n  // a note\n  skipped: r.c,\n})";
  for (const src of [html, ts]) {
    assert.deepEqual(
      translateParamFindings(src, valueOf).map((f) => f.anchor),
      ['SLOT_ADMIN.GENERATED{{count}}'],
    );
  }
});

test('every placeholder passed (shorthand too), a spread, or an unknown key: no finding', () => {
  assert.deepEqual(translateParamFindings("this.t.instant('A.OK', { created, skipped: 2 })", valueOf), []);
  assert.deepEqual(translateParamFindings("this.t.instant('A.OK', { ...params })", valueOf), []);
  assert.deepEqual(translateParamFindings("this.t.instant('X.Y', { a: 1 })", valueOf), []);
});
