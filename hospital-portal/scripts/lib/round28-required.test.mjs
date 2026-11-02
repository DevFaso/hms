// Round-28 guards (QA D1296, D1302, D1303, D1309, D1311): the shapes the
// earlier required-field and refusal sweeps could not see.
//
// 1. `refused-blank-undeclared` (D1296 / D1302 / D1303): a field the code
//    refuses WHEN BLANK (a refusing block that shows a REQUIRED / MISSING key
//    or COMMON.FIELD_REQUIRED and hands the field's id to refuseFields) whose
//    control does not declare `required`. The desk pay-collect "Facture" and
//    "Montant", the patient payment "Montant" and the recovery-contact value
//    were refused blank with no `required` and no star: the D1272 star rule
//    reads only controls that declare themselves required, so it never saw
//    them. A declared control is then held to its visible star by D1272.
// 2. `null-option` (D1296): an `<option [value]="null">` (or undefined) — the
//    string "null" reaches the model, passes a `!id` check and is posted as an
//    id (`POST /billing-invoices/null/payments`). `[ngValue]` keeps the null.
// 3. `encounter-option` (D1309): every `<option>` listing a patient's visits
//    is labelled through `shared/encounter-option-label.ts` (day, time,
//    department), so four same-day visits never read alike.
// 4. `born-agreement` (D1311): every "born" word shown for a patient comes
//    from `bornLabelKey(sex)`; the locale values agree with the sex they name.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';
import { balanced } from './js-scan.mjs';
import { tags } from './html-tags.mjs';
import { templatesIn } from './inline-templates.mjs';
import { declaresRequired } from './required-field-scan.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const i18nDir = join(appDir, '..', 'assets', 'i18n');
const rel = (file) => relative(appDir, file).split('\\').join('/');

// ── 1. refused blank, not declared ─────────────────────────────────────────

const BLANK_KEY = /\.instant\(\s*'[A-Z0-9_.]*(?:REQUIRED|MISSING)[A-Z0-9_]*'/;

/** The literal ids a refusing block hands to refuseFields as refused. */
function refusedIds(block) {
  const ids = new Set();
  for (const m of block.matchAll(/refuseFields\(/g)) {
    const args = balanced(block, m.index + m[0].length - 1);
    // The second argument: from the first top-level comma to the next.
    let depth = 0;
    const commas = [];
    for (let i = 1; i < args.length - 1; i++) {
      const ch = args[i];
      if ('([{'.includes(ch)) depth++;
      else if (')]}'.includes(ch)) depth--;
      else if (ch === ',' && depth === 0) commas.push(i);
    }
    const second = args.slice(commas[0] + 1, commas[1] ?? args.length - 1);
    for (const lit of second.matchAll(/'([A-Za-z][\w-]*)'/g)) ids.add(lit[1]);
  }
  for (const m of block.matchAll(/\bid:\s*'([A-Za-z][\w-]*)'/g)) ids.add(m[1]);
  for (const m of block.matchAll(/\?\s*'([A-Za-z][\w-]*)'\s*:\s*null/g)) ids.add(m[1]);
  return ids;
}

/** Each `if (…) { … }` block of a source that refuses with refuseFields. */
function refusingBlocks(source) {
  const out = [];
  for (const m of source.matchAll(/\bif\s*\(/g)) {
    const cond = balanced(source, m.index + m[0].length - 1);
    let i = m.index + m[0].length - 1 + cond.length;
    while (/\s/.test(source[i] ?? '')) i++;
    if (source[i] !== '{') continue;
    const block = balanced(source, i, '{', '}');
    if (block.includes('refuseFields(')) out.push(block);
  }
  return out;
}

export function refusedBlankUndeclared(ts, html) {
  const controls = new Map();
  for (const t of tags(html)) {
    if (t.type !== 'open') continue;
    const id = (t.attrs.get('id') ?? '').trim();
    if (id) controls.set(id, t);
  }
  const out = new Set();
  for (const block of refusingBlocks(ts)) {
    if (!BLANK_KEY.test(block) && !block.includes("'COMMON.FIELD_REQUIRED'")) continue;
    for (const id of refusedIds(block)) {
      const control = controls.get(id);
      if (control && !declaresRequired(control) && control.attrs.get('role') !== 'radiogroup') {
        out.add(id);
      }
    }
  }
  return [...out];
}

/** `file id` → why the field is refused blank without declaring it. */
const OTHER_BRANCH =
  'QA D1296 class (round 28): billing.ts is owned by fix/r28-portal-focus this round; flagged in the hand-back';
const REFUSED_UNDECLARED_ALLOWED = new Map([
  ['billing/billing.html line-edit-qty', OTHER_BRANCH],
  ['billing/billing.html line-edit-price', OTHER_BRANCH],
]);

test('QA D1296/D1302/D1303: a field refused when blank declares itself required', () => {
  const found = [];
  let scanned = 0;
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const html = file.replace(/\.ts$/, '.html');
    if (!existsSync(html)) continue;
    const ts = readFileSync(file, 'utf8');
    if (!ts.includes('refuseFields(')) continue;
    scanned++;
    for (const id of refusedBlankUndeclared(ts, readFileSync(html, 'utf8'))) {
      found.push(`${rel(html)} ${id}`);
    }
  }
  assert.ok(scanned >= 50, `the scan must read the refusing components, saw ${scanned}`);
  const unpinned = found.filter((k) => !REFUSED_UNDECLARED_ALLOWED.has(k));
  assert.deepEqual(
    unpinned,
    [],
    'put `required` (and " *" on its label) on the field: the code refuses it blank',
  );
  const stale = [...REFUSED_UNDECLARED_ALLOWED.keys()].filter((k) => !found.includes(k));
  assert.deepEqual(stale, [], 'the site was fixed: delete its pin');
});

test('QA D1296 guard: the scan sees the round-28 pay-collect and recovery-contact shapes', () => {
  const html = `<label for="invSelect">Facture</label><select id="invSelect"></select>
    <label for="payAmt">Montant</label><input id="payAmt" />
    <label for="recovery-value">Valeur</label><input id="recovery-value" />`;
  const pay = `submit() {
    const refusals = [];
    if (!invoiceId) refusals.push({ id: 'invSelect', message: required });
    if (refusals.length > 0) {
      refuseFields(this.host.nativeElement, [!invoiceId ? 'invSelect' : null, amountProblem ? 'payAmt' : null], PAY_FIELDS, this.translate.instant('COMMON.FIELD_REQUIRED'));
      return;
    }
  }
  save() {
    if (!value) {
      const message = this.translate.instant('PROFILE.RECOVERY_VALUE_REQUIRED');
      refuseFields(this.host.nativeElement, ['recovery-value'], ['recovery-value'], message);
      return;
    }
  }`;
  assert.deepEqual(refusedBlankUndeclared(pay, html).sort(), [
    'invSelect',
    'payAmt',
    'recovery-value',
  ]);
  const fixed = html
    .replaceAll('<select id', '<select required id')
    .replaceAll('<input id', '<input required id');
  assert.deepEqual(refusedBlankUndeclared(pay, fixed), []);
  // A value refused for its format (not blank) is not this rule.
  const format = `go() { if (bad) { refuseFields(h, ['payAmt'], all, this.translate.instant('X.TOO_HIGH')); return; } }`;
  assert.deepEqual(refusedBlankUndeclared(format, html), []);
});

// ── 2. [value]="null" on an option ─────────────────────────────────────────

export function nullValueOptions(html) {
  return tags(html)
    .filter((t) => t.type === 'open' && t.name === 'option')
    .filter((t) => /^(?:null|undefined)$/.test((t.attrs.get('[value]') ?? '').trim()))
    .map((t) => t.line);
}

test('QA D1296: no <option [value]="null"> (the string "null" is posted as an id)', () => {
  const found = [];
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    for (const line of nullValueOptions(text))
      found.push(`${rel(file)}:${line + (lineOffset ?? 0)}`);
  }
  assert.deepEqual(found, [], 'use [ngValue]="null": [value] turns null into the string "null"');
  assert.deepEqual(nullValueOptions('<option [value]="null">--</option>'), [1]);
  assert.deepEqual(
    nullValueOptions('<option [ngValue]="null">--</option><option value="">-</option>'),
    [],
  );
});

// ── 3. encounter options ───────────────────────────────────────────────────

/** The `<option>` text inside each `@for (x of …encounter…)` / `…visit…` loop. */
export function encounterOptionTexts(html) {
  const out = [];
  for (const m of html.matchAll(
    /@for\s*\(\s*(\w+)\s+of\s+([^;]*?(?:[Ee]ncounters?|[Vv]isits)\b[^;]*);/g,
  )) {
    const open = html.indexOf('{', m.index + m[0].length);
    if (open < 0) continue;
    const body = balanced(html, open, '{', '}');
    for (const o of body.matchAll(/<option\b[^>]*>([\s\S]*?)<\/option>/g)) {
      // Only an option labelled from the loop item lists visits (not a type picker).
      if (
        new RegExp(String.raw`\b${m[1]}\.(?:encounterDate|departmentName|id)\b|\(${m[1]}\)`).test(
          o[0],
        )
      ) {
        out.push(o[1]);
      }
    }
  }
  return out;
}

test('QA D1309: every encounter option is labelled with its day, time and department', () => {
  const offenders = [];
  let seen = 0;
  for (const file of walk(appDir, ['.html'])) {
    const html = readFileSync(file, 'utf8');
    const texts = encounterOptionTexts(html);
    if (texts.length === 0) continue;
    const ts = readFileSync(file.replace(/\.html$/, '.ts'), 'utf8');
    for (const text of texts) {
      seen++;
      const method = /\b(\w+)\(\s*\w+\s*\)/.exec(text)?.[1];
      const body = method
        ? new RegExp(String.raw`\n\s+${method}\([^)]*\)[^{]*\{([^}]*)\}`).exec(ts)?.[1]
        : null;
      if (!body?.includes('encounterOptionLabel('))
        offenders.push(`${rel(file)} ${method ?? text.trim()}`);
    }
  }
  assert.ok(seen >= 3, `the lab, prescription and discharge pickers must be read, saw ${seen}`);
  assert.deepEqual(
    offenders,
    [],
    'label the option through encounterOptionLabel (shared/encounter-option-label)',
  );
});

test('QA D1309 guard: the scan reads the round-28 lab picker and skips a type picker', () => {
  const html = `@for (e of patientEncounters(); track e.id) {
      <option [value]="e.id">{{ encounterLabel(e) }}</option>
    }
    @for (t of encounterTypes; track t) { <option [value]="t">{{ t }}</option> }`;
  assert.equal(encounterOptionTexts(html).length, 1);
});

// ── 4. born agreement ──────────────────────────────────────────────────────

const BORN_WORD = /(?:^|[^\p{L}])(N[ée]\(e\)|Née?|Nacid[oa](?:\/a)?|Born)(?=[^\p{L}]|$)/u;
/** Not a patient's birth line: a clinical outcome term. */
const BORN_NOT_A_PATIENT = new Set(['LABOR.LIVE_BIRTH']);

function flatten(obj, prefix = '', out = new Map()) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (typeof v === 'string') out.set(key, v);
    else flatten(v, key, out);
  }
  return out;
}

/** A "born" value that does not agree with the sex its key names. */
export function bornDisagreements(lang, entries) {
  const out = [];
  for (const [key, value] of entries) {
    const m = BORN_WORD.exec(value);
    if (!m || BORN_NOT_A_PATIENT.has(key)) continue;
    const word = m[1];
    const sex = /_FEMALE$/.test(key) ? 'F' : /_MALE$/.test(key) ? 'M' : 'N';
    const ok =
      lang === 'en' ||
      (sex === 'F' && /^(?:Née|Nacida)$/.test(word)) ||
      (sex === 'M' && /^(?:Né|Nacido)$/.test(word)) ||
      (sex === 'N' && /^(?:Né\(e\)|Nacido\/a)$/.test(word));
    if (!ok) out.push(`${lang} ${key} = ${value}`);
  }
  return out;
}

test('QA D1311: every "born" string agrees with the sex it is shown for', () => {
  const bad = [];
  for (const lang of ['fr', 'es', 'en']) {
    const json = JSON.parse(readFileSync(join(i18nDir, `${lang}.json`), 'utf8'));
    bad.push(...bornDisagreements(lang, flatten(json)));
  }
  assert.deepEqual(bad, [], 'a neutral key says Né(e) / Nacido/a; _FEMALE / _MALE keys agree');
  // The key is picked by the patient's sex, never written as the neutral key.
  const literal = [];
  for (const { file, text } of templatesIn(appDir)) {
    if (/'PATIENTS\.BORN(?:_FEMALE|_MALE)?'\s*\|\s*translate/.test(text)) literal.push(rel(file));
  }
  assert.deepEqual(literal, [], 'use {{ bornLabelKey(sex) | translate }} (shared/i18n/born-label)');
});

test('QA D1311 guard: the scan sees the round-28 Spanish "Nacido en" for everyone', () => {
  assert.deepEqual(bornDisagreements('es', new Map([['PATIENTS.BORN', 'Nacido en']])), [
    'es PATIENTS.BORN = Nacido en',
  ]);
  assert.deepEqual(bornDisagreements('es', new Map([['PATIENTS.BORN_FEMALE', 'Nacido en']])), [
    'es PATIENTS.BORN_FEMALE = Nacido en',
  ]);
  assert.deepEqual(
    bornDisagreements(
      'fr',
      new Map([
        ['PATIENTS.BORN_FEMALE', 'Née en'],
        ['PATIENTS.BORN', 'Né(e) en'],
      ]),
    ),
    [],
  );
});
