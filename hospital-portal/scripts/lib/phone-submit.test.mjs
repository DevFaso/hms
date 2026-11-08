// QA D1289 guard (round 27; D1211 class, submit side): a form that sends a
// phone number the server refuses.
//
// Every phone input carries `appPhoneField` (phone-field.test.mjs): the
// server's rule as an Angular validator and a message under the field — but
// only once the field is LEFT. Registration's "Enregistrer" never looked:
// "70a2b" typed and submitted went to `POST /users/admin-register` and came
// back as a 400.
//
// Rule, over every template: each phone field (`appPhoneField`, or a field
// group that holds one: `<app-provider-evidence-form>`) is read by the submit
// of its scope — the closest `<form>`, dialog or `[data-submit-scope]`
// section (the submit guard's `root()`) or, outside all of them, the
// template. Read means a button there carries the submit guard
// (`appSubmitGuard` / `[appSubmitGuard]`) and is not a secondary action (a
// non-submit button of a form that has its own submit): the guard refuses a
// refused number in its scope (shared/forms/submit-guard.directive.ts); or a
// submit handler there calls `refuseInvalidPhones(` / `invalidPhoneInputs(` /
// `isFacilityPhone(` (the server's rule).
// Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';
import { closeIndexOf } from './dialog-scan.mjs';
import { memberBody } from './disabled-control-scan.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/** Field groups whose own template holds a phone field (checked where they are used). */
const PHONE_GROUPS = new Set(['app-provider-evidence-form']);
const SELF_CHECK = /\brefuseInvalidPhones\(|\binvalidPhoneInputs\(|\bisFacilityPhone\(/;

const unquote = (v) => (v ?? '').replace(/^["']|["']$/g, '').trim();

function isScope(t) {
  if (t.name === 'form' || t.name === 'dialog' || t.attrs.has('data-submit-scope')) return true;
  if (t.attrs.has('appDialogA11y') || t.attrs.has('appModalDialog')) return true;
  const role = unquote(t.attrs.get('role'));
  if (role === 'dialog' || role === 'alertdialog') return true;
  return /(?:^|\s)modal(?:\s|$)/.test(unquote(t.attrs.get('class')));
}

/** The closest enclosing scope of token `index`, or -1 (the whole template). */
function scopeOf(tokens, index) {
  const stack = [];
  for (let i = 0; i < index; i++) {
    const t = tokens[i];
    if (t.type === 'close') {
      const at = stack.map((k) => tokens[k].name).lastIndexOf(t.name);
      if (at >= 0) stack.length = at;
    } else if (t.type === 'open' && !t.selfClosing && closeIndexOf(tokens, i) !== i) {
      stack.push(i);
    }
  }
  for (let k = stack.length - 1; k >= 0; k--) if (isScope(tokens[stack[k]])) return stack[k];
  return -1;
}

function handlerName(binding) {
  return /^\s*(?:this\.)?([A-Za-z_$][\w$]*)\s*\(/.exec(binding ?? '')?.[1] ?? null;
}

const isGuarded = (t) => t.attrs.has('[appSubmitGuard]') || t.attrs.has('appSubmitGuard');

/** Whether the submit of scope `scope` refuses a phone number the server refuses. */
function scopeChecksPhones(tokens, scope, componentTs) {
  const from = scope < 0 ? 0 : scope + 1;
  const to = scope < 0 ? tokens.length : closeIndexOf(tokens, scope);
  const inForm = scope >= 0 && tokens[scope].name === 'form';
  const buttons = tokens
    .slice(from, to)
    .filter((t) => t.type === 'open' && (t.name === 'button' || t.name === 'input'));
  const isSubmit = (b) =>
    b.name === 'button'
      ? unquote(b.attrs.get('type') || (inForm ? 'submit' : 'button')) === 'submit'
      : unquote(b.attrs.get('type')) === 'submit';
  // The guard reads every phone of its scope unless it is a secondary action:
  // a non-submit button of a form that has a submit of its own.
  const formHasSubmit = inForm && buttons.some(isSubmit);
  const readsAll = (b) => b.attrs.has('submitGuardRequired') || isSubmit(b) || !formHasSubmit;
  if (buttons.some((b) => isGuarded(b) && readsAll(b))) return true;
  const handlers = [
    inForm ? tokens[scope].attrs.get('(ngSubmit)') : null,
    inForm ? tokens[scope].attrs.get('(submit)') : null,
    ...buttons.map((b) => b.attrs.get('(click)')),
  ]
    .map(handlerName)
    .filter(Boolean);
  return handlers.some((h) => SELF_CHECK.test(memberBody(componentTs, h)));
}

/** The phone fields of a template that no submit checks: `{ line, what }`. */
export function uncheckedPhoneFindings(html, componentTs = '') {
  const tokens = tags(html);
  const out = [];
  tokens.forEach((t, i) => {
    if (t.type !== 'open') return;
    const phone = (t.name === 'input' && t.attrs.has('appPhoneField')) || PHONE_GROUPS.has(t.name);
    if (!phone) return;
    const scope = scopeOf(tokens, i);
    // A field group's own template has no submit: its host's submit reads it.
    if (scope < 0 && !tokens.some((x) => x.type === 'open' && x.name === 'button')) return;
    if (scopeChecksPhones(tokens, scope, componentTs)) return;
    out.push({ line: t.line, what: unquote(t.attrs.get('id')) || t.name });
  });
  return out;
}

test('QA D1289: every phone field is refused on submit when the server would refuse it', () => {
  const found = [];
  let phones = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const tsFile = file.endsWith('.html') ? file.replace(/\.html$/, '.ts') : file;
    const componentTs = existsSync(tsFile) ? readFileSync(tsFile, 'utf8') : '';
    phones += tags(text).filter(
      (t) => t.type === 'open' && t.name === 'input' && t.attrs.has('appPhoneField'),
    ).length;
    for (const f of uncheckedPhoneFindings(text, componentTs))
      found.push(`${rel}:${f.line + (lineOffset ?? 0)} ${f.what}`);
  }
  assert.ok(phones >= 15, `every phone field must be read, saw ${phones}`);
  assert.deepEqual(
    found,
    [],
    'guard the submit (appSubmitGuard submitGuardRequired) or call refuseInvalidPhones() in its handler',
  );
});

test('QA D1289 guard: the scan sees the registration shape and accepts the fixed ones', () => {
  // Registration: a form, an unguarded submit, a guarded OTP button that is not the submit.
  const html = `<form (ngSubmit)="onSubmit()">
      <input id="phonePrimary" type="tel" appPhoneField [(ngModel)]="form.phone" />
      <button type="button" [appSubmitGuard]="x" (click)="sendCode()">Code</button>
      <button type="submit">Enregistrer</button>
    </form>`;
  const before =
    '  onSubmit(): void {\n    if (refuseFields(h, blankFields(v, ids), ids, m)) return;\n  }\n';
  assert.equal(uncheckedPhoneFindings(html, before).length, 1);
  const after =
    '  onSubmit(): void {\n    const bad = invalidPhoneInputs(this.host.nativeElement);\n  }\n';
  assert.deepEqual(uncheckedPhoneFindings(html, after), []);
  const guarded = html.replace(
    '<button type="submit">',
    '<button type="submit" appSubmitGuard submitGuardRequired>',
  );
  assert.deepEqual(uncheckedPhoneFindings(guarded, before), []);
  // A field group's own template has nothing to submit.
  assert.deepEqual(uncheckedPhoneFindings('<input id="p" appPhoneField />'), []);
  // Its host is read like any form.
  const host = '<form (ngSubmit)="save()"><app-provider-evidence-form /><button>OK</button></form>';
  assert.equal(uncheckedPhoneFindings(host, '  save(): void {\n  }\n').length, 1);
});
