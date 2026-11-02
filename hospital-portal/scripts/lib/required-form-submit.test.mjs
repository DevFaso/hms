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
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';
import { closeIndexOf } from './dialog-scan.mjs';
import { memberBody } from './disabled-control-scan.mjs';
import { isFieldGroup, requiredFieldFindings } from './required-field-scan.mjs';

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
    if (!/\bSubmitGuardDirective\b/.test(ts)) missing.push(relative(appDir, tsFile));
  }
  assert.deepEqual(missing, [], 'add SubmitGuardDirective to the component imports');
});
