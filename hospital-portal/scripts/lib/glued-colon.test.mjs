// QA D1052 / D1002 / D1079 guard: a colon glued to the label before it.
//
// "Médicament: …", "MRN: CHW8973", "Statut: Confirmé", "Date de naissance:":
// French typography writes "Médicament : …" (a no-break space before the
// colon). The colon belongs to the language: {{ 'COMMON.COLON' | translate }}
// (en ":", fr "\u00a0:", es ":"), translate.instant('COMMON.COLON') in code, or
// a translation that carries it.
//
// Round 17 found it after translated labels on the pharmacy screens; rounds 18
// and 19 found it in literal text (`MRN:` on the nurse station), after
// interpolated labels and in the patient portal, because the first version of
// this guard read only `{{ 'X' | translate }}:` and pinned 92 sites. It now
// reads (glued-colon-scan.mjs):
//
//  - every template (.html and inline `template:`): the display text — a
//    label glued to its colon (`MRN:`, `>Statut:<`), a colon after any
//    interpolation (`{{ 'X' | translate }}:`, `{{ label }} :`), and a colon
//    literal composed in a binding or interpolation (`{{ x ? ': ' + x : '' }}`,
//    `[title]="d + ': ' + v"`); tag attributes, `@if (…)`, pipe arguments,
//    `{{ h }}:{{ m }}` times and `https:` never count;
//  - every non-spec .ts: a literal that composes label-colon-value for display
//    (`${label}: ${value}`, `'MRN: ' + mrn`, `label + ': ' + v`);
//  - fr.json: no French value glues a colon to the word before it.
//
// Per language: only French requires the space. English and Spanish write the
// colon glued ("Status:", "Estado:"), so en.json/es.json values are not read
// and COMMON.COLON is ":" there; what the guard enforces is that the markup and
// code never TYPE the colon, so each language's own rule applies.
//
// glued-colon-baseline.json pins only true false positives, each with its
// reason; a file not listed fails, and a listed one fails when its count moves.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { walk } from './walk.mjs';
import {
  gluedColonSites,
  gluedColons,
  gluedFrenchPunctuation,
  gluedFrenchValue,
  gluedPunctuationSites,
  gluedTsLabels,
} from './glued-colon-scan.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const appDir = join(here, '..', '..', 'src', 'app');
const i18nDir = join(here, '..', '..', 'src', 'assets', 'i18n');
const baselinePath = join(here, '..', 'glued-colon-baseline.json');

const rel = (file) => relative(appDir, file).replaceAll('\\', '/');

function currentSites() {
  const sites = {};
  const add = (file, line, what) => (sites[rel(file)] ??= []).push(`${line}: ${what}`);
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    // QA D1138: `?` `!` `;` typed in the markup, counted with the colons.
    for (const s of [...gluedColonSites(text), ...gluedPunctuationSites(text)]) {
      const line = text.slice(0, s.offset).split('\n').length + lineOffset;
      add(file, line, `${s.rule} | ${s.excerpt.trim()}`);
    }
  }
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const source = readFileSync(file, 'utf8');
    for (const s of gluedTsLabels(source)) {
      const line = source.slice(0, s.offset).split('\n').length;
      add(file, line, `ts | ${JSON.stringify(s.literal)}`);
    }
  }
  return sites;
}

test('QA D1079: no template or label string glues a colon, beyond the pinned false positives', () => {
  const sites = currentSites();
  const pinned = JSON.parse(readFileSync(baselinePath, 'utf8'));
  const problems = [];
  for (const [file, list] of Object.entries(sites)) {
    const pin = pinned[file];
    if (!pin || list.length > pin.count)
      problems.push(
        `${file}: ${list.length} glued colon(s)${pin ? `, pinned ${pin.count}` : ''} - use {{ 'COMMON.COLON' | translate }}\n    ${list.join('\n    ')}`,
      );
  }
  for (const [file, pin] of Object.entries(pinned)) {
    if (!pin.why || pin.why.startsWith('TODO')) problems.push(`${file}: pin without a reason`);
    const n = sites[file]?.length ?? 0;
    if (n < pin.count)
      problems.push(`${file}: stale pin (pinned ${pin.count}, found ${n}) - lower or delete it`);
  }
  assert.deepEqual(problems, []);
});

test('QA D1079: no French translation glues a colon to the word before it', () => {
  const fr = JSON.parse(readFileSync(join(i18nDir, 'fr.json'), 'utf8'));
  const glued = [];
  (function walkJson(node, path) {
    for (const [k, v] of Object.entries(node)) {
      if (v && typeof v === 'object') walkJson(v, `${path}${k}.`);
      else if (typeof v === 'string' && (gluedFrenchValue(v) || gluedFrenchPunctuation(v)))
        glued.push(`${path}${k} = ${v}`);
    }
  })(fr, '');
  assert.deepEqual(glued, []);
});

test('QA D1079: COMMON.COLON is the language rule (French: a no-break space, then the colon)', () => {
  const colon = (lang) =>
    JSON.parse(readFileSync(join(i18nDir, `${lang}.json`), 'utf8')).COMMON.COLON;
  assert.match(colon('fr'), /^[\u00a0\u202f]:$/);
  assert.equal(colon('en'), ':');
  assert.equal(colon('es'), ':');
});

test('QA D1079 guard: the scan sees every shape the defect had', () => {
  // round 17: after a translated label
  assert.equal(gluedColons("<span>{{ 'PHARMACY.MEDICATION' | translate }}:</span>"), 1);
  assert.equal(gluedColons("{{ 'PHARMACY.X' | translate: { n: 1 } }} :"), 1);
  // round 19: literal labels, interpolated labels, composed literals
  assert.equal(gluedColons('<span class="meta-item">MRN: {{ p.mrn }}</span>'), 1);
  assert.equal(gluedColons('<dt>Statut:</dt><dd>{{ s }}</dd>'), 1);
  assert.equal(gluedColons('<b>MRN:{{ mrn }}</b>'), 1);
  assert.equal(gluedColons('<li>{{ t.testType }}: {{ t.value }}</li>'), 1);
  assert.equal(gluedColons("{{ 'A' | translate }}{{ o.type ? ': ' + o.type : '' }}"), 1);
  assert.equal(gluedColons(`<div [title]="point.date + ': ' + point.amount"></div>`), 1);
  assert.equal(gluedColons('<input placeholder="Rechercher: nom" />'), 1);
  assert.equal(gluedColons('<p>L{{ n }} · C/S:\n {{ cs }}</p>'), 1);
  // .ts label strings
  assert.equal(gluedTsLabels('return `${label}: ${message}`;').length, 1);
  assert.equal(gluedTsLabels("const s = 'MRN: ' + p.mrn;").length, 1);
  assert.equal(gluedTsLabels("const s = label + ': ' + value;").length, 1);
  assert.equal(gluedTsLabels('const s = `Statut: ${status}`;').length, 1);
  // French locale values
  assert.equal(gluedFrenchValue('Date de naissance:'), true);
  assert.equal(gluedFrenchValue('Orientation à la sortie: {{v}}'), true);
  assert.equal(gluedFrenchValue('{{label}}: {{value}}'), true);
});

test('QA D1079 guard: display text only - Angular syntax, times, URLs and CSS are not labels', () => {
  const clean = [
    "{{ 'PHARMACY.MEDICATION' | translate }}{{ 'COMMON.COLON' | translate }}",
    `<span [class.x]="a ? b : c" (click)="go(a ? 1 : 2)">x</span>`,
    '@if (a) { <b>{{ x | translate: { n: 1 } }}</b> } @else { y }',
    '@for (r of rows; track r.id) { {{ r.id | slice: 0 : 8 }} }',
    "{{ d | date: 'HH:mm' }} <span>10:30</span> {{ h }}:{{ m }}",
    '<a href="https://e-keneya.com">site</a> <div style="color: red; margin: 0">x</div>',
    '<style>.a::ng-deep b { color: red; }</style><p>Ratio 1:2</p>',
    "<!-- Statut: a comment --><p>Statut{{ 'COMMON.COLON' | translate }} ok</p>",
    "@let label = ok ? 'A' : 'B'; <p>{{ label }}</p>",
  ];
  for (const html of clean) assert.equal(gluedColons(html), 0, html);
  const cleanTs = [
    "return serviceId + ':' + hospitalId;",
    'const key = `staff:${id}`;',
    'const t = `${h}:${m}`;',
    "console.error('Failed after retries:', e);",
    "const u = 'https://' + host;",
    'type D = `phrase:${string}`;',
    "const css = 'color: red';",
    '// Statut: a comment\nconst s = `${label}${colon} ${v}`;',
  ];
  for (const source of cleanTs) assert.equal(gluedTsLabels(source).length, 0, source);
  assert.equal(gluedFrenchValue('Envoi vers {{host}}:{{port}}.'), false);
  assert.equal(gluedFrenchValue('Statut\u00a0: {{s}} à 10:30, voir https://x'), false);
});

test('QA D1138: the scan sees a glued ? ! ; after a name, a word or an interpolation', () => {
  // round 20: the imaging cancel prompt, "… pour Zongo, Fatimata?"
  const prompt = `{{ 'IMAGING.ORDER_FOR' | translate }}
    <strong>{{ deletingItem()?.patientDisplayName }}</strong
    >?`;
  assert.equal(gluedPunctuationSites(prompt).length, 1);
  assert.equal(gluedPunctuationSites('<p>Bienvenue {{ name }}!</p>').length, 1);
  assert.equal(gluedPunctuationSites('<p>Attention; risque</p>').length, 1);
  const clean = [
    "<strong>{{ n }}</strong>{{ 'COMMON.QUESTION_MARK' | translate }}",
    '<p>a&nbsp;b &amp; c &#160;d</p>',
    '{{ a ?? b }} {{ x?.y }} <span [hidden]="!ok">x</span>',
    '<p>Continuer ?</p>',
  ];
  for (const html of clean) assert.equal(gluedPunctuationSites(html).length, 0, html);
  assert.equal(gluedFrenchPunctuation('Supprimer {{name}}?'), true);
  assert.equal(gluedFrenchPunctuation('Continuer\u00a0?'), false);
  assert.equal(gluedFrenchPunctuation('Voir https://x.y/?a=1'), false);
});

test('QA D1138: COMMON.QUESTION_MARK and EXCLAMATION_MARK are the language rule', () => {
  const common = (lang) => JSON.parse(readFileSync(join(i18nDir, `${lang}.json`), 'utf8')).COMMON;
  assert.match(common('fr').QUESTION_MARK, /^[\u00a0\u202f]\?$/);
  assert.match(common('fr').EXCLAMATION_MARK, /^[\u00a0\u202f]!$/);
  for (const lang of ['en', 'es']) {
    assert.equal(common(lang).QUESTION_MARK, '?');
    assert.equal(common(lang).EXCLAMATION_MARK, '!');
  }
});
