// QA D1211 guard (round 23, portal half): a phone input the portal does not
// check with the server's rule.
//
// #1011 put one rule (`@PhoneNumber`: E.164 or 6 to 15 national digits) on all
// 27 phone fields of the server's write requests. The portal checked only the
// account's main phone (login, user creation), so "appeler le voisin" typed in
// a secondary, contact, guarantor or pharmacy phone came back as a banner
// after the save, away from the field.
//
// Every phone input of every template (`type="tel"`, or an id / name / model
// that says phone) carries the shared `appPhoneField` directive
// (shared/forms/phone-field.directive.ts: the server's rule, the message on the
// field), and the component that owns the template imports it: Angular ignores
// an attribute directive nobody imported, silently. A site with its own check,
// at least as strict, is pinned with why.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const appDir = join(here, '..', '..', 'src', 'app');

/** Phone inputs with their own check, each with why. */
const PINS = new Map([
  [
    'login/login.html#bs-phone',
    "the platform's first account: its own check is the stricter toE164 (QA D906/D1170), shown in #bs-phone-error",
  ],
  [
    'users/user-list.html#phoneNumber',
    "a user's login phone: its own check (PHONE_PATTERN, QA D1170) marks the field from user-list.ts",
  ],
]);

/** Each `<input …>` tag of a template, with its line. */
export function inputTags(html) {
  const out = [];
  let from = 0;
  for (;;) {
    const start = html.indexOf('<input', from);
    if (start < 0) break;
    let i = start + 6;
    let quote = null;
    for (; i < html.length; i++) {
      const ch = html[i];
      if (quote) {
        if (ch === quote) quote = null;
      } else if (ch === '"' || ch === "'") quote = ch;
      else if (ch === '>') break;
    }
    out.push({ tag: html.slice(start, i + 1), line: html.slice(0, start).split('\n').length });
    from = i + 1;
  }
  return out;
}

/** Whether an input tag is a phone field: `type="tel"`, or an id, name or model that says phone. */
export function isPhoneInput(tag) {
  if (/\btype\s*=\s*"tel"/i.test(tag)) return true;
  const names = [
    ...tag.matchAll(/\b(?:id|name|\[\(ngModel\)\]|\[ngModel\]|formControlName)\s*=\s*"([^"]*)"/g),
  ].map((m) => m[1]);
  // A verification code sent to the phone is not a phone number.
  return names.some((n) => /phone/i.test(n) && !/phone-?code|phoneCode|otp/i.test(n));
}

/** The id of an input tag, for its pin key. */
function idOf(tag) {
  return /\bid\s*=\s*"([^"]*)"/.exec(tag)?.[1] ?? '?';
}

/** The component source that names `htmlFile` as its templateUrl, or null. */
function owner(htmlFile, tsFiles) {
  const name = basename(htmlFile);
  return (
    tsFiles.find(
      (t) => dirname(t.file) === dirname(htmlFile) && t.text.includes(`templateUrl: './${name}'`),
    ) ?? null
  );
}

function scan() {
  const tsFiles = walk(appDir, ['.ts'])
    .filter((f) => !f.endsWith('.spec.ts'))
    .map((file) => ({ file, text: readFileSync(file, 'utf8') }));
  const found = [];
  const templates = walk(appDir, ['.html']).map((file) => ({
    file,
    text: readFileSync(file, 'utf8'),
    ts: null,
  }));
  for (const t of tsFiles) {
    const inline = /template:\s*`([\s\S]*?)`/.exec(t.text);
    if (inline) templates.push({ file: t.file, text: inline[1], ts: t });
  }
  for (const tpl of templates) {
    const rel = relative(appDir, tpl.file).replaceAll('\\', '/');
    const component = tpl.ts ?? owner(tpl.file, tsFiles);
    for (const { tag, line } of inputTags(tpl.text)) {
      if (!isPhoneInput(tag)) continue;
      found.push({
        site: `${rel}#${idOf(tag)}`,
        line,
        rel,
        directive: /\bappPhoneField\b/.test(tag),
        imported:
          !!component && /\bimports:\s*\[[^\]]*\bPhoneFieldDirective\b/.test(component.text),
      });
    }
  }
  return found;
}

test('QA D1211: every phone input checks the server rule on the field, or is pinned with why', () => {
  const found = scan();
  assert.ok(found.length >= 20, `the phone inputs must be found, saw ${found.length}`);
  const unchecked = found
    .filter((f) => !f.directive && !PINS.has(f.site))
    .map((f) => `${f.rel}:${f.line} ${f.site}`);
  assert.deepEqual(unchecked, [], 'add appPhoneField (shared/forms/phone-field.directive.ts)');
  const notImported = found
    .filter((f) => f.directive && !f.imported)
    .map((f) => `${f.rel}:${f.line} ${f.site}`);
  assert.deepEqual(
    notImported,
    [],
    'import PhoneFieldDirective in the component: Angular ignores it otherwise',
  );
  const stale = [...PINS.keys()].filter(
    (site) => !found.some((f) => f.site === site && !f.directive),
  );
  assert.deepEqual(stale, [], 'a pinned input is gone or now checked: delete its pin');
});

test('QA D1211 guard: the scan sees the round-23 shapes', () => {
  const html = [
    '<input id="gua-phone" type="text" maxlength="20" [(ngModel)]="guarantorForm.phone" />',
    '<input id="ecPhone" type="tel" [(ngModel)]="emergencyContactPhone" />',
    '<input id="ok" type="tel" appPhoneField [(ngModel)]="x" />',
    '<input id="rw-code" [(ngModel)]="phoneCode" />',
    '<input id="name" [(ngModel)]="form.lastName" (ngModelChange)="a > b" />',
  ].join('\n');
  const tags = inputTags(html);
  assert.equal(tags.length, 5);
  assert.deepEqual(
    tags.map((t) => isPhoneInput(t.tag)),
    [true, true, true, false, false],
  );
  assert.equal(/\bappPhoneField\b/.test(tags[2].tag), true);
});
