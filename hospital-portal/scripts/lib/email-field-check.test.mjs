// QA D1179 guard (round 21): an e-mail address is checked where it is typed.
//
// "Envoyer la facture" sent `pas-un-email` to the server and the clerk read
// the server's raw bean-validation text in a toast, away from the field. A
// form's `type="email"` does not stop that: the portal never submits a native
// form (Angular adds `novalidate`), so the browser's own check never runs.
//
// Every template input that takes an e-mail address (its id, name or bound
// model names an e-mail, and it is not a checkbox, a password or a code) must
// belong to a component that checks the address itself before sending: the
// shared `isEmailAddress` / `invalidEmailAddresses`
// (src/app/shared/forms/email-address.ts) or Angular's `Validators.email`.
//
// The allowlist holds the forms that existed before this guard, each with its
// defect id. It shrinks only: a fixed form must be deleted from it (a stale
// entry fails), and a new e-mail field is never added to it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const NAMES_EMAIL = /email/i;
const NOT_AN_ADDRESS = /password|code|message|title|hint|verified|notif/i;
const NOT_TEXT_TYPES = new Set(['checkbox', 'radio', 'hidden', 'submit', 'button']);
const CHECKS_ADDRESS = /\bisEmailAddress\(|\binvalidEmailAddresses\(|\bValidators\.email\b/;

const PENDING =
  'QA D1179 class: the server answers a malformed address; the client-side check is pending (tasklist standing debt, round 21)';

/** Forms that take an e-mail address and do not check it yet: `file::input id`. */
const ALLOWLIST = new Map([
  ['departments/department-list.html::dEmail', PENDING],
  ['hospitals/hospital-list.html::hEmail', PENDING],
  ['login/login.html::bs-email', PENDING],
  ['login/login.html::ra-email', PENDING],
  ['organizations/organization-list.html::orgEmail', PENDING],
  ['patients/coverage-tab/coverage-tab.component.html::gua-email', PENDING],
  [
    'patients/patient-demographics-edit/patient-demographics-edit.component.html::demoEmail',
    PENDING,
  ],
  ['patients/patient-form.html::email', PENDING],
  ['pharmacy/pharmacy-registry.html::pEmail', PENDING],
  ['platform/platform.html::editContactEmail', PENDING],
  ['platform/platform.html::regContactEmail', PENDING],
  ['profile/profile.html::editEmail', PENDING],
  ['provider/provider-profile/provider-profile.html::profile-email', PENDING],
  ['provider/provider-staff-register/provider-staff-register.html::register-email', PENDING],
  ['super-admin/providers/provider-form/provider-form.html::provider-email', PENDING],
  ['users/user-list.html::email', PENDING],
  ['verify-email/verify-email.html::verify-email', PENDING],
]);

/** Is this `<input>` one that takes an e-mail address? */
export function isEmailInput(tag) {
  if (tag.type !== 'open' || tag.name !== 'input') return false;
  const type = (tag.attrs.get('type') ?? 'text').toLowerCase();
  if (NOT_TEXT_TYPES.has(type)) return false;
  const naming = ['id', 'name', '[(ngModel)]', '[ngModel]', 'formControlName']
    .map((k) => tag.attrs.get(k) ?? '')
    .join(' ');
  if (type === 'email') return !NOT_AN_ADDRESS.test(naming);
  return NAMES_EMAIL.test(naming) && !NOT_AN_ADDRESS.test(naming);
}

function componentSource(file) {
  if (!file.endsWith('.html')) return readFileSync(file, 'utf8');
  const ts = file.replace(/\.html$/, '.ts');
  return existsSync(ts) ? readFileSync(ts, 'utf8') : '';
}

/** `file::id` of every e-mail input whose component never checks the address. */
export function uncheckedEmailInputs(dir = appDir) {
  const out = [];
  for (const { file, text } of templatesIn(dir)) {
    const inputs = tags(text).filter(isEmailInput);
    if (inputs.length === 0 || CHECKS_ADDRESS.test(componentSource(file))) continue;
    const rel = relative(dir, file).replaceAll('\\', '/');
    for (const input of inputs) {
      out.push(
        `${rel}::${input.attrs.get('id') ?? input.attrs.get('name') ?? `line ${input.line}`}`,
      );
    }
  }
  return out;
}

test('QA D1179: every e-mail field is checked by its form before it is sent', () => {
  const found = uncheckedEmailInputs();
  const fresh = found.filter((key) => !ALLOWLIST.has(key));
  assert.deepEqual(
    fresh,
    [],
    'an e-mail field whose form sends the address unchecked: call isEmailAddress / invalidEmailAddresses ' +
      '(shared/forms/email-address.ts) and refuse it on the field (refuseFields)',
  );
  const stale = [...ALLOWLIST.keys()].filter((key) => !found.includes(key));
  assert.deepEqual(stale, [], 'a fixed form is still on the allowlist: delete its entry');
});

test('the scan sees the invoice e-mail shape and the checked one', () => {
  const [plain] = tags('<input id="email-to" type="text" [ngModel]="emailTo()" />');
  assert.equal(isEmailInput(plain), true);
  const [typed] = tags('<input id="x" type="email" [(ngModel)]="form.contact" />');
  assert.equal(isEmailInput(typed), true);
  const [attach] = tags('<input type="checkbox" [ngModel]="emailAttachPdf()" />');
  assert.equal(isEmailInput(attach), false);
  const [code] = tags('<input id="emailChangeCode" type="text" />');
  assert.equal(isEmailInput(code), false);
  assert.match('const bad = invalidEmailAddresses(to);', CHECKS_ADDRESS);
});
