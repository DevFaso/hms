// QA D1193 / D1089 / D1124 guard (round 22): a form with required fields that
// posts empty.
//
// The lab instrument and lab inventory "Créer" on an empty form sent the
// request: the fields carried `required`, but Angular's forms switch the
// browser's own check off (`novalidate`) and the submit button checked
// nothing. The reader got the server's raw "ne doit pas être vide" or a
// generic toast. 26 forms of the portal had the same shape.
//
// Rule, over every template (each `.html` and each inline `template:`): a
// `<form>` holding a `required` / `[required]` control has its submit button
// (`type="submit"`, or a button with no type) carry `[appSubmitGuard]`
// (a reason the component computes) or `submitGuardRequired` (the guard reads
// the form's required fields at click time and names the blank ones;
// shared/forms/submit-guard.directive.ts) — or its submit handler refuses by
// itself, field by field (`refuseFields`, `focusFirstInvalid`, `blankFields`:
// the patient registration form and five others name their own fields; the
// guard would replace their wording with the generic one). Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { relative, join, dirname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';
import { closeIndexOf } from './dialog-scan.mjs';
import { memberBody } from './disabled-control-scan.mjs';
import {
  isFieldGroup,
  listedUndeclaredFindings,
  partialGuardFindings,
  requiredFieldFindings,
  unstarredRequiredFindings,
} from './required-field-scan.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const FIELD = new Set(['input', 'select', 'textarea']);

/** A submit handler that refuses blank required fields itself, naming each one. */
const SELF_REFUSAL = /\brefuseFields\(|\bfocusFirstInvalid\(|\bblankFields\(/;

/** The method a binding calls (`save($event)` → `save`). */
function handlerName(binding) {
  return /^\s*(?:this\.)?([A-Za-z_$][\w$]*)\s*\(/.exec(binding ?? '')?.[1] ?? null;
}

/** The forms of a template that hold a required field and whose submit is unguarded: `{ line }`. */
export function unguardedRequiredForms(html, componentTs = '') {
  const tokens = tags(html);
  const out = [];
  tokens.forEach((t, i) => {
    if (t.type !== 'open' || t.name !== 'form') return;
    const inner = tokens.slice(i + 1, closeIndexOf(tokens, i)).filter((x) => x.type === 'open');
    const required = inner.some(
      (x) => FIELD.has(x.name) && (x.attrs.has('required') || x.attrs.has('[required]')),
    );
    if (!required) return;
    const submits = inner.filter(
      (x) =>
        x.name === 'button' && (x.attrs.get('type') ?? 'submit').replace(/["']/g, '') === 'submit',
    );
    const guarded = submits.some(
      (b) => b.attrs.has('[appSubmitGuard]') || b.attrs.has('submitGuardRequired'),
    );
    if (guarded) return;
    const handlers = [t.attrs.get('(ngSubmit)'), t.attrs.get('(submit)')]
      .concat(submits.map((b) => b.attrs.get('(click)')))
      .map(handlerName)
      .filter(Boolean);
    if (handlers.some((h) => SELF_REFUSAL.test(memberBody(componentTs, h)))) return;
    out.push({ line: t.line });
  });
  return out;
}

test('QA D1193: no form with required fields can be sent empty', () => {
  const found = [];
  let forms = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    forms += tags(text).filter((t) => t.type === 'open' && t.name === 'form').length;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const tsFile = file.endsWith('.html') ? file.replace(/\.html$/, '.ts') : file;
    const componentTs = existsSync(tsFile) ? readFileSync(tsFile, 'utf8') : '';
    for (const f of unguardedRequiredForms(text, componentTs))
      found.push(`${rel}:${f.line + (lineOffset ?? 0)}`);
  }
  assert.ok(forms >= 50, `every form must be read, saw ${forms}`);
  assert.deepEqual(
    found,
    [],
    'put appSubmitGuard submitGuardRequired (or [appSubmitGuard]="reason") on the submit button',
  );
});

test('QA D1193 guard: the scan sees the lab instrument form and accepts the guarded one', () => {
  const broken = `<form (ngSubmit)="save()">
      <label>Nom * <input type="text" name="name" required [(ngModel)]="form.name" /></label>
      <button type="submit" class="btn-primary" [disabled]="saving()">Créer</button>
    </form>`;
  assert.equal(unguardedRequiredForms(broken).length, 1);
  const fixed = broken.replace(
    '[disabled]="saving()"',
    '[disabled]="saving()" appSubmitGuard submitGuardRequired',
  );
  assert.deepEqual(unguardedRequiredForms(fixed), []);
  const reasoned = broken.replace('[disabled]="saving()"', '[appSubmitGuard]="!form.name"');
  assert.deepEqual(unguardedRequiredForms(reasoned), []);
  const noType = broken.replace('type="submit" ', '');
  assert.equal(unguardedRequiredForms(noType).length, 1);
  // A handler that refuses field by field itself is not this class.
  const selfChecked = broken.replace('(ngSubmit)="save()"', '(ngSubmit)="onSubmit()"');
  const ts =
    '  onSubmit(): void {\n    if (refuseFields(this.host, blankFields(v, ids), ids, msg)) return;\n  }\n';
  assert.deepEqual(unguardedRequiredForms(selfChecked, ts), []);
  const passThrough = '  onSubmit(): void {\n    this.save();\n  }\n';
  assert.equal(unguardedRequiredForms(selfChecked, passThrough).length, 1);
  const optional = '<form><input name="q" /><button>Go</button></form>';
  assert.deepEqual(unguardedRequiredForms(optional), []);
});

// ── QA D1245 (D1089 / D1124 NOT_FIXED again, round 25) ──────────────────────
// The rule above read only a `<form>` whose controls carry `required`. The
// prescription dialog is a `.modal` with a `(click)` "Créer", and its "Nom du
// patient *" search input had only the star: `patientId: ''` went out and the
// server answered a generic 400. The scan now reads every required input — a
// starred label's control included — in every form, dialog or
// `data-submit-scope` section (lib/required-field-scan.mjs).

const PICKER_TEMPLATE = 'shared/patient-picker/patient-picker.component.html';

/** The selector a component's source declares (`selector: 'app-x'`). */
function selectorOf(ts) {
  return /selector:\s*'([a-z][\w-]*)'/.exec(ts)?.[1] ?? null;
}

function scanAll() {
  const sources = [];
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const tsFile = file.endsWith('.html') ? file.replace(/\.html$/, '.ts') : file;
    const componentTs = existsSync(tsFile) ? readFileSync(tsFile, 'utf8') : '';
    sources.push({ rel, text, lineOffset: lineOffset ?? 0, componentTs });
  }
  const fieldGroups = new Set();
  const groupFiles = new Set();
  for (const s of sources) {
    if (s.rel === PICKER_TEMPLATE || !isFieldGroup(s.text)) continue;
    const selector = selectorOf(s.componentTs);
    if (selector) {
      fieldGroups.add(selector);
      groupFiles.add(s.rel);
    }
  }
  const found = [];
  let required = 0;
  for (const s of sources) {
    // The shared picker's `[required]` is its host's: checked at every use.
    if (s.rel === PICKER_TEMPLATE) continue;
    required += (s.text.match(/\srequired[\s>=]|\[required\]|aria-required/g) ?? []).length;
    for (const f of requiredFieldFindings(s.text, s.componentTs, fieldGroups)) {
      if (groupFiles.has(s.rel) && f.rule === 'unchecked') continue;
      found.push(`${s.rel}:${f.line + s.lineOffset} ${f.rule} ${f.id || '(no id)'}`);
    }
  }
  return { found, required, fieldGroups };
}

test('QA D1245: every required input is declared and checked by its submit', () => {
  const { found, required, fieldGroups } = scanAll();
  assert.ok(required >= 400, `every required input must be read, saw ${required}`);
  assert.ok(fieldGroups.has('app-provider-evidence-form'), 'field groups are found');
  assert.deepEqual(
    found,
    [],
    'star-undeclared: put `required` on the starred control (on <app-patient-picker> too); ' +
      'unchecked: put `submitGuardRequired` on the submit button of its form or dialog ' +
      '(or list the id in [submitGuardFields]); a section that is neither carries data-submit-scope',
  );
});

test('QA D1245 guard: the scan sees the round-25 prescription dialog', () => {
  const broken = `<div class="modal" role="dialog">
      <label for="rx-patientSearch">Nom du patient *</label>
      <input id="rx-patientSearch" [value]="patientQuery()" />
      <label for="rx-medName">Médicament *</label>
      <input id="rx-medName" required [(ngModel)]="form.medicationName" />
      <button class="btn-primary" (click)="submitForm()"
        [appSubmitGuard]="!form.medicationName" [submitGuardFields]="['rx-medName']">Créer</button>
    </div>`;
  assert.deepEqual(
    requiredFieldFindings(broken).map((f) => `${f.rule} ${f.id}`),
    ['star-undeclared rx-patientSearch'],
  );
  // Declared, but the guard neither lists it nor reads every required field.
  const declared = broken.replace(
    '<input id="rx-patientSearch"',
    '<input id="rx-patientSearch" required',
  );
  assert.deepEqual(
    requiredFieldFindings(declared).map((f) => `${f.rule} ${f.id}`),
    ['unchecked rx-patientSearch'],
  );
  const fixed = declared.replace(
    '(click)="submitForm()"',
    '(click)="submitForm()" submitGuardRequired',
  );
  assert.deepEqual(requiredFieldFindings(fixed), []);
  const listed = declared.replace("['rx-medName']", "['rx-patientSearch', 'rx-medName']");
  assert.deepEqual(requiredFieldFindings(listed), []);
  // The shared picker declares it with `required`.
  const picker = `<div class="modal"><label for="p">Patient *</label>
      <app-patient-picker inputId="p" [selected]="x()" />
      <button class="btn-primary" appSubmitGuard submitGuardRequired (click)="go()">OK</button></div>`;
  assert.equal(requiredFieldFindings(picker)[0]?.rule, 'star-undeclared');
  assert.deepEqual(
    requiredFieldFindings(picker.replace('<app-patient-picker', '<app-patient-picker required')),
    [],
  );
  // Outside any form or dialog the guard has nowhere to read the field.
  const loose = `<section><label for="a">A *</label><input id="a" required />
      <button appSubmitGuard submitGuardRequired (click)="go()">OK</button></section>`;
  assert.equal(requiredFieldFindings(loose)[0]?.rule, 'unchecked');
  assert.deepEqual(
    requiredFieldFindings(loose.replace('<section>', '<section data-submit-scope>')),
    [],
  );
  // A field group is checked where it is used.
  const host = `<form><app-evidence /><button type="submit">OK</button></form>`;
  assert.equal(requiredFieldFindings(host, '', new Set(['app-evidence']))[0]?.rule, 'unchecked');
  assert.deepEqual(
    requiredFieldFindings(
      host.replace('type="submit"', 'type="submit" appSubmitGuard submitGuardRequired'),
      '',
      new Set(['app-evidence']),
    ),
    [],
  );
  assert.ok(isFieldGroup('<label>A *</label><input required />'));
  assert.ok(!isFieldGroup('<form><input required /><button>OK</button></form>'));
});

test('QA D1245: a template that uses the submit guard imports it', () => {
  // A bare `appSubmitGuard submitGuardRequired` on a button of a component that
  // does not import SubmitGuardDirective compiles as two inert attributes: the
  // click goes through and nothing is checked.
  const missing = [];
  for (const { file, text } of templatesIn(appDir)) {
    if (!/\bappSubmitGuard\b|\bsubmitGuardRequired\b/.test(text)) continue;
    const tsFile = file.endsWith('.html') ? file.replace(/\.html$/, '.ts') : file;
    const ts = existsSync(tsFile) ? readFileSync(tsFile, 'utf8') : '';
    if (!/\bSubmitGuardDirective\b/.test(ts))
      missing.push(relative(appDir, tsFile).replaceAll('\\', '/'));
  }
  assert.deepEqual(missing, [], 'add SubmitGuardDirective to the component imports');
});

// ── QA D1256 / D1257 (D1089 / D1124 / D1245 NOT_FIXED again, round 26) ──────
// The guard read the form's required controls only when its expression let
// the click through, so an empty prescription named only the drug; and a
// guard that tested the patient without listing it (Nouvelle consultation, the
// lab order) named everything but the patient. The directive now reads every
// field it is about on every refusal (submit-guard.directive.ts); this scan
// makes sure every guard is about its whole form.

test('QA D1256: every guarded submit reads every required field of its form', () => {
  const found = [];
  let guards = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    guards += (text.match(/\bappSubmitGuard\b/g) ?? []).length;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const f of partialGuardFindings(text))
      found.push(`${rel}:${f.line + (lineOffset ?? 0)} leaves out ${f.missing.join(', ')}`);
  }
  assert.ok(guards >= 150, `every guarded submit must be read, saw ${guards}`);
  assert.deepEqual(
    found,
    [],
    'put submitGuardRequired on the button (or list every required id in [submitGuardFields]); ' +
      'a secondary action with fields of its own sits in its own data-submit-scope',
  );
});

test('QA D1256 guard: the scan sees the round-26 consultation dialog', () => {
  const broken = `<div class="modal" role="dialog">
      <label for="enc-patient">Patient *</label>
      <input id="enc-patient" required data-submit-blank="true" />
      <label for="enc-date">Date *</label>
      <input id="enc-date" required />
      <button class="btn-primary" (click)="submitForm()"
        [appSubmitGuard]="!form.patientId || !form.date" [submitGuardFields]="['enc-date']">Créer</button>
    </div>`;
  assert.deepEqual(
    partialGuardFindings(broken).map((f) => f.missing.join(',')),
    ['enc-patient'],
  );
  const fixed = broken.replace(
    '(click)="submitForm()"',
    '(click)="submitForm()" submitGuardRequired',
  );
  assert.deepEqual(partialGuardFindings(fixed), []);
  const listed = broken.replace("['enc-date']", "['enc-patient', 'enc-date']");
  assert.deepEqual(partialGuardFindings(listed), []);
  // A secondary action beside a submit that reads the whole form.
  const secondary = `<form>
      <input id="a" required /><input id="code" />
      <button type="button" [appSubmitGuard]="!code && 'Code'" [submitGuardFields]="['code']">OK</button>
      <button type="submit" appSubmitGuard submitGuardRequired>Créer</button>
    </form>`;
  assert.deepEqual(partialGuardFindings(secondary), []);
  // Its own scope: the fields around it only.
  const ownScope = `<div class="modal"><input id="a" required />
      <div data-submit-scope><textarea id="m"></textarea>
      <button [appSubmitGuard]="!m && 'Message'">Envoyer</button></div></div>`;
  assert.deepEqual(partialGuardFindings(ownScope), []);
  const sameScope = ownScope.replace('<div data-submit-scope>', '<div>');
  assert.deepEqual(
    partialGuardFindings(sameScope).map((f) => f.missing.join(',')),
    ['a'],
  );
});

// ── QA D1272 (round 26): a required field with no star ─────────────────────

function translator(lang) {
  const json = JSON.parse(
    readFileSync(join(appDir, '..', 'assets', 'i18n', `${lang}.json`), 'utf8'),
  );
  return (key) => {
    const v = key.split('.').reduce((o, p) => (o == null ? undefined : o[p]), json);
    return typeof v === 'string' ? v : null;
  };
}

test('QA D1272: every required field with a label shows the star, in every language', () => {
  const found = new Set();
  let templates = 0;
  for (const lang of ['fr', 'en', 'es']) {
    const tr = translator(lang);
    for (const { file, text, lineOffset } of templatesIn(appDir)) {
      if (lang === 'fr') templates++;
      const rel = relative(appDir, file).replaceAll('\\', '/');
      for (const f of unstarredRequiredFindings(text, tr))
        found.add(`${rel}:${f.line + (lineOffset ?? 0)} ${f.id || '(no id)'}`);
    }
  }
  assert.ok(templates > 200, `every template must be read, saw ${templates}`);
  assert.deepEqual(
    [...found].sort(),
    [],
    'write " *" after the label text (the guard names the field; the star tells the reader first)',
  );
});

test('QA D1272 guard: the scan sees the round-26 lab test definition', () => {
  const tr = (k) => ({ 'L.CODE': 'Code du test', 'L.TYPE': "Type d'étude *" })[k] ?? null;
  const broken = `<div class="modal"><label for="defTestCode">{{ 'L.CODE' | translate }}</label>
      <input id="defTestCode" required /></div>`;
  assert.deepEqual(
    unstarredRequiredFindings(broken, tr).map((f) => f.id),
    ['defTestCode'],
  );
  assert.deepEqual(unstarredRequiredFindings(broken.replace('}}</label>', '}} *</label>'), tr), []);
  // The star in the translation, a conditional star, a wrapping label.
  assert.deepEqual(
    unstarredRequiredFindings(
      `<label for="t">{{ 'L.TYPE' | translate }}</label><input id="t" required />`,
      tr,
    ),
    [],
  );
  assert.deepEqual(
    unstarredRequiredFindings(
      `<label for="r">Réf{{ need ? ' *' : '' }}</label><input id="r" [required]="need" />`,
      tr,
    ),
    [],
  );
  assert.equal(
    unstarredRequiredFindings(`<label>{{ 'L.CODE' | translate }} <input required /></label>`, tr)
      .length,
    1,
  );
  // Named only by aria-label: no label to carry the star.
  assert.deepEqual(unstarredRequiredFindings('<input aria-label="Motif" required />', tr), []);
});

// QA D1272, the other half: a field a guard refuses blank (listed in its
// `submitGuardFields`) that neither declares `required` nor shows a star. The
// handoff's "Patient" and "Sens" were the reported pair. Each site below was
// found by the first run of the rule; each is pinned with its owner and
// reason, and a pin fails once its site is fixed, so the list only shrinks.
// The mortality and transfusion patient pickers were fixed in this PR
// (`required` on the shared picker).
const MAR_BRANCH =
  'D1272 — nurse-station / eMAR, owned by fix/r26-mar-schedule (handoff stars are its scope)';
const CONDITIONAL =
  'D1272 debt — shown only for one action of the dialog; required + star to add with a test of that action';
const SECONDARY =
  'D1272 debt — a secondary step of a larger form (OTP / verification code), not a form field';
const CONSENT =
  'D1272 debt — an attestation checkbox; its label is the consent sentence, star to add';
const REASON = 'D1272 debt — the single reason field of a confirm dialog; required + star to add';
const LISTED_UNDECLARED_ALLOWED = new Map([
  ['admissions/admissions.html:adm-bed-select', CONDITIONAL],
  ['bed-board/bed-board.html:precaution-reason', REASON],
  ['bed-board/bed-board.html:transfer-destination', CONDITIONAL],
  ['bed-board/bed-board.html:override-confirm', CONDITIONAL],
  ['bed-board/bed-board.html:override-reason', CONDITIONAL],
  ['bed-board/bed-board.html:transfer-reason', CONDITIONAL],
  ['checkout/checkout-dialog/checkout-dialog.component.html:checkout-diagnoses', CONDITIONAL],
  ['checkout/checkout-dialog/checkout-dialog.component.html:checkout-instructions', CONDITIONAL],
  ['encounters/encounters.html:addendum-content', REASON],
  ['lab/lab-approval-queue/lab-approval-queue.html:rejection-reason', REASON],
  ['lab/lab-results/lab-results.html:readBackValue', CONDITIONAL],
  ['lock-screen/lock-screen.html:unlock-password', REASON],
  ['nurse-station/emar/emar.component.html:emar-hold-reason', MAR_BRANCH],
  ['nurse-station/nurse-station.html:cn-narrative', MAR_BRANCH],
  ['nurse-station/nursing-intake-form/nursing-intake-form.component.html:nursingNotes', MAR_BRANCH],
  [
    'patient-portal/my-appointments/pre-checkin-form/pre-checkin-form.component.html:preCheckinConsent',
    CONSENT,
  ],
  ['patients/patient-form.html:otpCode', SECONDARY],
  ['pharmacy/dispensing.html:verifyAttest', CONSENT],
  ['profile/profile.html:editEmail', CONDITIONAL],
  ['profile/profile.html:emailChangeCode', SECONDARY],
  ['profile/profile.html:pwdCurrent', CONDITIONAL],
  ['profile/profile.html:pwdNew', CONDITIONAL],
  ['profile/profile.html:pwdConfirm', CONDITIONAL],
  ['profile/profile.html:verification-code', SECONDARY],
  ['reception/checkin-dialog/checkin-dialog.component.html:checkinIdentityConfirmed', CONSENT],
]);

test('QA D1272: a field its guard refuses blank declares itself required (pins shrink only)', () => {
  const found = [];
  const seen = new Set();
  for (const { file, text } of templatesIn(appDir)) {
    const rel = relative(appDir, file).split(sep).join('/');
    for (const f of listedUndeclaredFindings(text)) {
      const site = `${rel}:${f.id}`;
      if (LISTED_UNDECLARED_ALLOWED.has(site)) seen.add(site);
      else found.push(site);
    }
  }
  assert.deepEqual(
    found,
    [],
    'put `required` on the field (and " *" on its label): its guard already refuses it blank',
  );
  const stale = [...LISTED_UNDECLARED_ALLOWED.keys()].filter((s) => !seen.has(s));
  assert.deepEqual(stale, [], 'the site was fixed: delete its line from LISTED_UNDECLARED_ALLOWED');
});

test('QA D1272 guard: the scan sees the round-26 handoff', () => {
  const broken = `<div role="dialog">
      <label for="ho-direction">Sens</label><select id="ho-direction"></select>
      <button [appSubmitGuard]="!dir" [submitGuardFields]="['ho-direction']">OK</button></div>`;
  assert.deepEqual(
    listedUndeclaredFindings(broken).map((f) => f.id),
    ['ho-direction'],
  );
  assert.deepEqual(
    listedUndeclaredFindings(
      broken.replace('<select id="ho-direction"', '<select id="ho-direction" required'),
    ),
    [],
  );
});
