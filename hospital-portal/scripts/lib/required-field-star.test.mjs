// QA D1272 guard (round 26): a field the submit guard requires shows the
// required star.
//
// The nurse station's handoff ("Nouvelle transmission") refused an empty
// submit with "Patient, Sens" while neither label carried the star; the
// lab-test definition did the same for "Code du test" and "Nom du test". The
// round-25 rule (`required-form-submit.test.mjs`, `star-undeclared`) reads the
// other direction only: a starred label must declare `required`.
//
// Rule `listed-unstarred`: every id a button lists in `[submitGuardFields]`
// (the fields its guard names when it refuses) has a `<label for="id">` whose
// text shows `*`, unless the label is visually hidden (`sr-only`: the control
// then declares `required` / `aria-required` for assistive technology), or the
// field is pinned below with why. Pins are of two kinds: a field required only
// for one of the dialog's actions or as one of several (its label says so, or
// a star would mislead), and the known instances in screens owned by another
// round-26 branch (D1272 class, to be removed by that branch). A pin that no
// longer matches fails, so the list only shrinks.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/** `file id` → why the field shows no star. */
const PINNED = new Map([
  // Required for one action of the dialog, or one of several: no star.
  [
    'nurse-station/emar/emar.component.html emar-hold-reason',
    'required to hold or refuse only (not to give); the label says "(required to hold or refuse)"',
  ],
  [
    'nurse-station/nurse-station.html cn-narrative',
    'a care note needs one of its parts (narrative, DAR or SOAP), not the narrative itself',
  ],
  [
    'nurse-station/nursing-intake-form/nursing-intake-form.component.html nursingNotes',
    'the intake needs an assessment (notes or any assessed item), not the notes themselves',
  ],
  // QA D1280 (round 27): the discharge summary needs a diagnosis OR
  // instructions, not each; a star on both would say each is required.
  [
    'checkout/checkout-dialog/checkout-dialog.component.html checkout-diagnoses',
    'the summary needs a diagnosis or instructions (one of two), not each',
  ],
  [
    'checkout/checkout-dialog/checkout-dialog.component.html checkout-instructions',
    'the summary needs a diagnosis or instructions (one of two), not each',
  ],
  // Listed only when the reader clears an address they had: required then only.
  ['profile/profile.html editEmail', 'listed only when an existing address is cleared'],
  // A one-time code: a secondary step of the profile form, refused by its own
  // short reason; `required` would make the profile's save refuse it too.
  ['profile/profile.html emailChangeCode', 'secondary verification step of the profile form'],
  ['profile/profile.html verification-code', 'secondary verification step of the profile form'],
]);

// QA D1280 (D1272 NOT_FIXED in round 27): the round-26 list of "another
// branch's screens" (admissions, bed board, lab approval, read-back, the
// password form) outlived the round; round 27 starred them all and deleted
// the list. The three lab-approval fields were never unstarred: their star is
// in the translation, which the scan now reads (as the declared-required rule
// does), in every language.

const unquote = (v) => (v ?? '').replace(/^["']|["']$/g, '').trim();

/** The ids every `[submitGuardFields]` of a template lists. */
function listedIds(tokens) {
  const ids = new Set();
  for (const t of tokens) {
    if (t.type !== 'open') continue;
    const list = t.attrs.get('[submitGuardFields]');
    if (!list) continue;
    for (const m of unquote(list).matchAll(/'([^']+)'/g)) ids.add(m[1]);
  }
  return ids;
}

/**
 * The text of each `<label for>` up to its first nested control (a label may
 * wrap its control), and whether it is visually hidden.
 */
function labelsOf(html, tokens) {
  const labels = new Map();
  tokens.forEach((t, i) => {
    if (t.type !== 'open' || t.name !== 'label') return;
    const id = unquote(t.attrs.get('for'));
    if (!id) return;
    let text = '';
    for (let k = i + 1; k < tokens.length; k++) {
      const n = tokens[k];
      if (
        (n.type === 'close' && n.name === 'label') ||
        ['input', 'select', 'textarea'].includes(n.name)
      ) {
        text = html.slice(t.end ?? 0, n.start ?? 0);
        break;
      }
    }
    const hidden = /\bsr-only\b/.test(unquote(t.attrs.get('class')));
    labels.set(id, { text, hidden });
  });
  return labels;
}

/** A label's words: each `'KEY' | translate` read as the key's value in one language. */
function labelWords(text, translate) {
  return text.replaceAll(/\{\{([\s\S]*?)\}\}/g, (_, expr) =>
    [...expr.matchAll(/'([^']*)'/g)]
      .map((m) =>
        /^[A-Z][A-Z0-9_]*(?:\.[A-Z0-9_]+)+$/.test(m[1]) ? (translate(m[1]) ?? '') : m[1],
      )
      .join(' '),
  );
}

/**
 * @param translators key → value, one per language; a label is starred when
 *   its template text or its translation shows `*` in EVERY language.
 */
export function unstarredListedFields(html, translators = [() => null]) {
  const tokens = tags(html);
  const labels = labelsOf(html, tokens);
  const out = [];
  for (const id of listedIds(tokens)) {
    const label = labels.get(id);
    if (!label || label.hidden) continue;
    if (!translators.every((tr) => labelWords(label.text, tr).includes('*'))) out.push(id);
  }
  return out;
}

function translator(lang) {
  const json = JSON.parse(
    readFileSync(join(appDir, '..', 'assets', 'i18n', `${lang}.json`), 'utf8'),
  );
  return (key) => {
    const v = key.split('.').reduce((o, p) => (o == null ? undefined : o[p]), json);
    return typeof v === 'string' ? v : null;
  };
}

function scanAll() {
  const found = [];
  let listed = 0;
  const translators = ['fr', 'en', 'es'].map(translator);
  for (const { file, text } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    listed += listedIds(tags(text)).size;
    for (const id of unstarredListedFields(text, translators)) found.push(`${rel} ${id}`);
  }
  return { found, listed };
}

test('QA D1272: a field the submit guard names when it refuses shows the required star', () => {
  const { found, listed } = scanAll();
  assert.ok(listed >= 120, `the scan must read the guarded fields, saw ${listed}`);
  const unpinned = found.filter((k) => !PINNED.has(k));
  assert.deepEqual(unpinned, [], 'add " *" to the label of each field its submit guard requires');
  const stale = [...PINNED.keys()].filter((k) => !found.includes(k));
  assert.deepEqual(stale, [], 'remove the pins that no longer match (the list only shrinks)');
});

test('QA D1272: the scan sees the round-26 handoff dialog', () => {
  const broken = `<div class="modal" role="dialog">
      <label for="ho-patient">Patient</label>
      <app-patient-picker inputId="ho-patient" />
      <label for="ho-direction">Sens</label>
      <select id="ho-direction"></select>
      <label for="ho-note">Note</label><textarea id="ho-note"></textarea>
      <button [appSubmitGuard]="!a || !b" [submitGuardFields]="['ho-patient', 'ho-direction']">OK</button>
    </div>`;
  assert.deepEqual(unstarredListedFields(broken), ['ho-patient', 'ho-direction']);
  const fixed = broken.replace('>Patient<', '>Patient *<').replace('>Sens<', '>Sens *<');
  assert.deepEqual(unstarredListedFields(fixed), []);
  // A label that wraps its control is read up to the control.
  const wrapped = `<label for="r"><span>Motif</span><textarea id="r"></textarea></label>
      <button [submitGuardFields]="['r']">OK</button>`;
  assert.deepEqual(unstarredListedFields(wrapped), ['r']);
  // A visually hidden label: the control declares the requirement instead.
  const hidden = wrapped.replace('<label for="r">', '<label class="sr-only" for="r">');
  assert.deepEqual(unstarredListedFields(hidden), []);
  // The star in the translation, in every language.
  const keyed = `<label for="t">{{ 'L.TYPE' | translate }}</label><select id="t"></select>
      <button [submitGuardFields]="['t']">OK</button>`;
  const fr = (k) => ({ 'L.TYPE': "Type d'étude *" })[k] ?? null;
  const esUnstarred = (k) => ({ 'L.TYPE': 'Tipo de estudio' })[k] ?? null;
  assert.deepEqual(unstarredListedFields(keyed, [fr]), []);
  assert.deepEqual(unstarredListedFields(keyed, [fr, esUnstarred]), ['t']);
});

test('QA D1280 guard: the scan sees the round-27 assign-bed field', () => {
  const bed = `<div class="modal" role="dialog">
      <label for="adm-bed-select">{{ 'ADMISSIONS.SELECT_BED' | translate }}</label>
      <select id="adm-bed-select"></select>
      <button [appSubmitGuard]="!bed" [submitGuardFields]="['adm-bed-select']">OK</button></div>`;
  assert.deepEqual(unstarredListedFields(bed), ['adm-bed-select']);
  assert.deepEqual(unstarredListedFields(bed.replace('}}</label>', '}} *</label>')), []);
});
