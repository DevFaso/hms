import { test } from 'node:test';
import assert from 'node:assert/strict';

import { translateParamFindings } from './translate-params-scan.mjs';

const values = new Map([
  ['SLOT_ADMIN.GENERATED', '{{count}} slots generated'],
  ['A.OK', '{{created}} made, {{skipped}} skipped'],
]);
const valueOf = (k) => values.get(k);

test('QA D1035: the slot generation shape is caught, in a template and in a component', () => {
  const html =
    "{{ 'SLOT_ADMIN.GENERATED' | translate: { created: r.a, templates: r.b, skipped: r.c } }}";
  const ts =
    "this.translate.instant('SLOT_ADMIN.GENERATED', {\n  created: r.a,\n  // a note\n  skipped: r.c,\n})";
  for (const src of [html, ts]) {
    assert.deepEqual(
      translateParamFindings(src, valueOf).map((f) => f.anchor),
      ['SLOT_ADMIN.GENERATED{{count}}'],
    );
  }
});

test('every placeholder passed (shorthand too), a spread, or an unknown key: no finding', () => {
  assert.deepEqual(
    translateParamFindings("this.t.instant('A.OK', { created, skipped: 2 })", valueOf),
    [],
  );
  assert.deepEqual(translateParamFindings("this.t.instant('A.OK', { ...params })", valueOf), []);
  assert.deepEqual(translateParamFindings("this.t.instant('X.Y', { a: 1 })", valueOf), []);
});

test('round 18 (D1035 hunt): a call with NO parameters is caught when the value names one', () => {
  for (const src of [
    "{{ 'A.OK' | translate }}",
    '[title]="\'A.OK\' | translate"',
    "this.translate.instant('A.OK')",
    "this.translate.get('A.OK')",
  ]) {
    assert.deepEqual(
      translateParamFindings(src, valueOf).map((f) => `${f.rule} ${f.anchor}`),
      ['no-params A.OK{{created}}', 'no-params A.OK{{skipped}}'],
      src,
    );
  }
  // A key whose value names nothing, or a call whose argument is a variable: no finding.
  assert.deepEqual(translateParamFindings("{{ 'X.Y' | translate }}", valueOf), []);
  assert.deepEqual(translateParamFindings("this.t.instant('A.OK', params)", valueOf), []);
});

test('round 18 (D1035 hunt): a placeholder only one locale names is checked too', () => {
  // The gate hands every locale's value, joined: French renamed {{created}} to {{count}}.
  const joined = (k) => (k === 'A.FR' ? '{{created}} made\n{{count}} créés' : undefined);
  assert.deepEqual(
    translateParamFindings("this.t.instant('A.FR', { created: 1 })", joined).map((f) => f.anchor),
    ['A.FR{{count}}'],
  );
});

test('round 18 (D1035 hunt): every locale names the same placeholders as English', async () => {
  const { readFileSync } = await import('node:fs');
  const { resolve } = await import('node:path');
  const { flatten, placeholderMismatches } = await import('./translate-params-scan.mjs');
  const dir = resolve(import.meta.dirname, '..', '..', 'src', 'assets', 'i18n');
  const byLocale = new Map(
    ['en', 'fr', 'es'].map((l) => [
      l,
      flatten(JSON.parse(readFileSync(resolve(dir, `${l}.json`), 'utf8'))),
    ]),
  );
  assert.deepEqual(placeholderMismatches(byLocale), []);
  // The check sees a renamed placeholder.
  assert.deepEqual(
    placeholderMismatches(
      new Map([
        ['en', new Map([['K', '{{created}} made']])],
        ['fr', new Map([['K', '{{count}} créés']])],
      ]),
    ),
    ['fr K en={created} fr={count}'],
  );
});
