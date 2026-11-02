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
]);

/** D1272 class in screens owned by fix/r26-portal-forms (round 26): that branch removes them. */
const OTHER_BRANCH = [
  'admissions/admissions.html adm-bed-select',
  'bed-board/bed-board.html precaution-reason',
  'bed-board/bed-board.html transfer-destination',
  'bed-board/bed-board.html transfer-reason',
  'bed-board/bed-board.html override-reason',
  'checkout/checkout-dialog/checkout-dialog.component.html checkout-diagnoses',
  'checkout/checkout-dialog/checkout-dialog.component.html checkout-instructions',
  'lab/lab-approval-queue/lab-approval-queue.html study-type',
  'lab/lab-approval-queue/lab-approval-queue.html study-date',
  'lab/lab-approval-queue/lab-approval-queue.html rejection-reason',
  'lab/lab-results/lab-results.html readBackValue',
  'lab/lab-test-config/lab-test-config.html defTestCode',
  'lab/lab-test-config/lab-test-config.html defTestName',
  'maternity/labor-tab.html labor-onset',
  'prescriptions/prescriptions.html cds-override-reason',
  'prescriptions/prescriptions.html rx-sign-reauth-secret',
  'profile/profile.html editEmail',
  'profile/profile.html emailChangeCode',
  'profile/profile.html pwdCurrent',
  'profile/profile.html pwdNew',
  'profile/profile.html pwdConfirm',
  'profile/profile.html verification-code',
  'super-admin/providers/provider-detail/provider-detail.html decision-reason',
];
for (const key of OTHER_BRANCH) PINNED.set(key, 'D1272 class, fix/r26-portal-forms');

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

export function unstarredListedFields(html) {
  const tokens = tags(html);
  const labels = labelsOf(html, tokens);
  const out = [];
  for (const id of listedIds(tokens)) {
    const label = labels.get(id);
    if (!label || label.hidden) continue;
    if (!label.text.includes('*')) out.push(id);
  }
  return out;
}

function scanAll() {
  const found = [];
  let listed = 0;
  for (const { file, text } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    listed += listedIds(tags(text)).size;
    for (const id of unstarredListedFields(text)) found.push(`${rel} ${id}`);
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
});
