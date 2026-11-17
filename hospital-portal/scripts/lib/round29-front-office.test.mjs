// Round-29 front-office guards (fix/r29-portal-front-office). Each rule is
// the class of a round-29 defect, read over the covered directories (the
// same list the behavioural form spec drives: lib/form-behaviour-registry.mjs
// COVERED_DIRS) or the whole locale files:
//
// 1. `dash` (D1328): `?? '—'` shows an empty cell for "" (an optional field
//    left blank); a missing value goes through `| dash`.
// 2. `key-label` (D1323): `'PREFIX.' + row.field | translate` prints the raw
//    key for a value outside the dictionary ("RECEPTION.URGENTISSIME",
//    "RECEPTION."); such labels go through `| keyLabel: 'PREFIX.'`.
// 3. (D1340 is guarded by fix/r29-portal-clinical's date-time-seconds.test.mjs
//    and its shared `dateTime` pipe, which this branch uses; the self-test
//    below only checks the pattern still parses.)
// 4. `required-in-words` (D1326): a locale label that says "(obligatoire)" /
//    "(required)" / "(obligatorio)" instead of a star and `required`.
// 5. `gendered-family` (D1327, the D1311 class): a key family describing the
//    patient (BASE, BASE_FEMALE, BASE_MALE) is complete in every locale, its
//    French and Spanish words agree with the sex the key names, and no
//    template names a family's key directly (genderedKey(base, sex) picks it).
// 6. `guard-preempts-handler` (D1342 / D1320, the D1256 class): a button
//    carrying a submit guard whose click handler refuses its fields one by one
//    with their own reasons (FieldRefusal objects). The guard refused the
//    blank fields first, with the generic "required" text, and the handler's
//    other reasons (an amount above the balance, a negative copay) were never
//    seen on the same press. The handler alone refuses.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';
import { memberBody } from './disabled-control-scan.mjs';
import { closeIndexOf } from './dialog-scan.mjs';
import { COVERED_DIRS } from './form-behaviour-registry.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const i18nDir = join(appDir, '..', 'assets', 'i18n');
const rel = (file) => relative(appDir, file).split('\\').join('/');

function coveredTemplates() {
  const out = [];
  for (const dir of COVERED_DIRS) {
    const abs = join(appDir, dir);
    if (existsSync(abs)) out.push(...templatesIn(abs));
  }
  return out;
}

function lineOf(text, index, offset = 0) {
  return text.slice(0, index).split('\n').length + offset;
}

/** Every `[file:line] match` of `re` in the covered templates. */
function hits(re, templates = coveredTemplates()) {
  const out = [];
  for (const { file, text, lineOffset } of templates) {
    for (const m of text.matchAll(re))
      out.push(`${rel(file)}:${lineOf(text, m.index, lineOffset)}`);
  }
  return out;
}

const DASH_RE = /\?\?\s*'—'/g;
const KEY_LABEL_RE =
  /'[A-Z][A-Z0-9_]*(?:\.[A-Z0-9_]+)*\.'\s*\+\s*[A-Za-z_]\w*(?:\??\.\w+)+\s*\|\s*translate/g;
const DATE_MEDIUM_RE = /date:\s*'medium'/g;

export function dashFindings(text) {
  return [...text.matchAll(DASH_RE)].length;
}

test('QA D1328: a missing value reads "—" through | dash, never `?? \'—\'`', () => {
  assert.deepEqual(hits(DASH_RE), [], 'write {{ value | dash }} (shared/pipes/dash.pipe.ts)');
});

test('QA D1323: a label built from a server value never prints its raw key', () => {
  assert.deepEqual(hits(KEY_LABEL_RE), [], "write {{ row.field | keyLabel: 'PREFIX.' }}");
});

test('QA D1323 / D1328 / D1340 guard: the rules see the round-29 shapes', () => {
  const t = [
    {
      file: join(appDir, 'x.html'),
      lineOffset: 0,
      text: `<td>{{ p.address ?? '—' }}</td>
<td>{{ 'RECEPTION.' + entry.priority | translate }}</td>
<td>{{ d.at | date: 'medium' }}</td>
@for (p of priorities; track p) { <option>{{ 'RECEPTION.' + p | translate }}</option> }`,
    },
  ];
  assert.equal(hits(DASH_RE, t).length, 1);
  assert.equal(
    hits(KEY_LABEL_RE, t).length,
    1,
    'a loop variable over constants is not a row value',
  );
  assert.equal(hits(DATE_MEDIUM_RE, t).length, 1);
});

// ── 4. required said in words ─────────────────────────────────────────────

const REQUIRED_WORDS = /\((?:obligatoire|obligatoires|required|obligatorio|obligatoria)\)/i;

function flatten(obj, prefix = '', out = new Map()) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (typeof v === 'string') out.set(key, v);
    else flatten(v, key, out);
  }
  return out;
}

const locales = Object.fromEntries(
  ['en', 'fr', 'es'].map((l) => [
    l,
    flatten(JSON.parse(readFileSync(join(i18nDir, `${l}.json`), 'utf8'))),
  ]),
);

export function requiredInWords(entries) {
  return [...entries].filter(([, v]) => REQUIRED_WORDS.test(v)).map(([k, v]) => `${k} = ${v}`);
}

test('QA D1326: no label says "(obligatoire)" in words: it shows a star and declares required', () => {
  const bad = Object.entries(locales).flatMap(([l, e]) =>
    requiredInWords(e).map((x) => `${l} ${x}`),
  );
  assert.deepEqual(bad, []);
});

test('QA D1326 guard: the rule sees the round-29 cancel-reason label', () => {
  assert.deepEqual(requiredInWords(new Map([['A.STATUS_REASON_LABEL', 'Motif (obligatoire)']])), [
    'A.STATUS_REASON_LABEL = Motif (obligatoire)',
  ]);
});

// ── 5. gendered families ──────────────────────────────────────────────────

const FEMININE = /\b(?:enregistrée|Née|registrada|Nacida|inscrite|admise|inscrita|ingresada)\b/u;
const MASCULINE =
  /\b(?:enregistré|Né|registrado|Nacido|inscrit|admis|inscrito|ingresado)(?![\p{L}(/])/u;
const NEUTRAL =
  /(?:enregistré\(e\)|Né\(e\)|registrado\/a|Nacido\/a|inscrit\(e\)|admis\(e\)|inscrito\/a|ingresado\/a)/u;

/** The families (BASE with BASE_FEMALE / BASE_MALE) and what is wrong with each. */
export function genderedFamilyFindings(byLocale) {
  const out = [];
  const bases = new Set();
  for (const entries of Object.values(byLocale)) {
    for (const k of entries.keys()) {
      const m = /^(.*)_(?:FEMALE|MALE)$/.exec(k);
      if (m && m[1].startsWith('PATIENTS.')) bases.add(m[1]);
    }
  }
  for (const base of bases) {
    for (const [lang, entries] of Object.entries(byLocale)) {
      for (const [suffix, rule] of [
        ['', NEUTRAL],
        ['_FEMALE', FEMININE],
        ['_MALE', MASCULINE],
      ]) {
        const key = `${base}${suffix}`;
        const value = entries.get(key);
        if (value === undefined) out.push(`${lang} ${key} missing`);
        else if (lang !== 'en' && !rule.test(value)) out.push(`${lang} ${key} = ${value}`);
      }
    }
  }
  return { bases, out };
}

test('QA D1327: every word that describes the patient agrees with the sex its key names', () => {
  const { bases, out } = genderedFamilyFindings(locales);
  assert.ok(bases.has('PATIENTS.REGISTERED_IN_MANY'), 'the duplicate card family is read');
  assert.deepEqual(out, []);
  const direct = [];
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    for (const base of bases) {
      const re = new RegExp(
        `'${base.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?:_FEMALE|_MALE)?'\\s*\\|\\s*translate`,
        'g',
      );
      for (const m of text.matchAll(re))
        direct.push(`${rel(file)}:${lineOf(text, m.index, lineOffset)}`);
    }
  }
  assert.deepEqual(
    direct,
    [],
    'pick the key with genderedKey(base, sex) (shared/i18n/born-label.ts)',
  );
});

test('QA D1327 guard: the rule sees the round-29 "registrado en 2 hospitales" for a woman', () => {
  const byLocale = {
    es: new Map([
      ['PATIENTS.REGISTERED_IN_MANY', 'registrado/a en {{count}} hospitales'],
      ['PATIENTS.REGISTERED_IN_MANY_FEMALE', 'registrado en {{count}} hospitales'],
      ['PATIENTS.REGISTERED_IN_MANY_MALE', 'registrado en {{count}} hospitales'],
    ]),
  };
  assert.deepEqual(genderedFamilyFindings(byLocale).out, [
    'es PATIENTS.REGISTERED_IN_MANY_FEMALE = registrado en {{count}} hospitales',
  ]);
});

// ── 6. a submit guard that pre-empts the handler's own refusals ──────────

const REFUSAL_OBJECTS = /refuseFields\([^;]*?(?:\{\s*id:|refusals)/s;

/** Guarded buttons of `html` whose click handler refuses field by field with reasons. */
export function preemptingGuards(html, ts) {
  const out = [];
  const tokens = tags(html);
  // A submit button of a form sends the form's (ngSubmit) handler.
  const formOf = new Map();
  tokens.forEach((t, i) => {
    if (t.type !== 'open' || t.name !== 'form') return;
    const end = closeIndexOf(tokens, i);
    for (let j = i + 1; j < end; j++) if (!formOf.has(j)) formOf.set(j, t);
  });
  tokens.forEach((t, i) => {
    if (t.type !== 'open' || t.name !== 'button') return;
    if (!t.attrs.has('appSubmitGuard') && !t.attrs.has('[appSubmitGuard]')) return;
    const submit = (t.attrs.get('type') ?? 'submit').replace(/"/g, '') === 'submit';
    const handler =
      t.attrs.get('(click)') ?? (submit ? formOf.get(i)?.attrs.get('(ngSubmit)') : undefined) ?? '';
    const fn = /(\w+)\(/.exec(handler.replace(/^"|"$/g, ''))?.[1];
    if (!fn) return;
    if (REFUSAL_OBJECTS.test(memberBody(ts, fn) ?? '')) out.push(fn);
  });
  return out;
}

test('QA D1342 / D1320: a handler that refuses each field with its reason is not pre-empted by a guard', () => {
  const found = [];
  for (const { file, text } of templatesIn(appDir)) {
    if (!file.endsWith('.html')) continue;
    const tsFile = file.replace(/\.html$/, '.ts');
    if (!existsSync(tsFile)) continue;
    for (const fn of preemptingGuards(text, readFileSync(tsFile, 'utf8'))) {
      found.push(`${rel(file)} ${fn}`);
    }
  }
  assert.deepEqual(found, [], 'drop the guard: the handler refuses every field at once');
});

test('QA D1342 guard: the rule sees the round-29 payment button', () => {
  const html = `<button appSubmitGuard submitGuardRequired (click)="recordPayment()">OK</button>`;
  const ts = `
  recordPayment(): void {
    const refusals = this.paymentRefusals();
    refuseFields(this.host.nativeElement, refusals, IDS);
  }
  private paymentRefusals(): FieldRefusal[] {
    return [{ id: 'payment-amount', message: 'x' }];
  }
`;
  // The reasons are built in a helper; the handler hands them on.
  assert.deepEqual(preemptingGuards(html, ts), ['recordPayment']);
  assert.deepEqual(
    preemptingGuards(html.replace('appSubmitGuard submitGuardRequired ', ''), ts),
    [],
  );
  // QA D1320: the check-in's guarded submit sends the form's (ngSubmit).
  const form = `<form (ngSubmit)="recordPayment()"><button type="submit" [appSubmitGuard]="x">OK</button></form>`;
  assert.deepEqual(preemptingGuards(form, ts), ['recordPayment']);
});
