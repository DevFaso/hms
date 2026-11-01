// QA D1202 / D1198 / D1199 / D1197 and D1131 / D1190 / D1195 / D1208 guards
// (round 22): the two choke points every screen of the portal goes through.
//
// 1. Focus after an action. The keyboard fell to the page body after a
//    submit, a panel toggle or a close on screens nobody had named (nurse
//    station, bed board, admissions, profile, sharing, recalls, family
//    access) — each round fixed the named handlers. shared/a11y/focus-rescue.ts
//    moves focus to the nearest surviving part of the page whenever the
//    focused control goes away; app.config.ts must install it.
// 2. Escape over typed text. Each round fixed the dialogs it named; the next
//    found another. shared/a11y/escape-keeps-typed-text.ts lives in every
//    dialog directive the dialog gate accepts (appModalDialog, appDialogFocus,
//    appDialogA11y), so every dialog of the portal asks before Escape drops
//    what the reader typed. (appDrawerDialog is the phone navigation drawer: a
//    menu, no form; not a dialog the gate counts for a form.)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { relative, sep } from 'node:path';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';
import { closeIndexOf } from './dialog-scan.mjs';
import { walk } from './walk.mjs';

const DIALOG_ATTRS = ['appModalDialog', 'appDialogFocus', 'appDialogA11y'];

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const read = (rel) => readFileSync(join(appDir, rel), 'utf8');

const DIALOG_DIRECTIVES = [
  'shared/a11y/modal-dialog.directive.ts',
  'shared/a11y/dialog-focus.directive.ts',
  'shared/a11y/dialog-a11y.directive.ts',
];

export function installsFocusRescue(config) {
  return /provideAppInitializer\(\s*\(\)\s*=>\s*installFocusRescue\(/.test(config);
}

export function keepsTypedText(directive) {
  return (
    /new EscapeKeepsTypedText\(/.test(directive) &&
    /typedText\.destroy\(\)/.test(directive) &&
    // The constructor-time listener covers Escape inside the box; a
    // document-level Escape path (focus outside the box) must ask too.
    (!/document:keydown/.test(directive) || /typedText\.intercept\(event\)/.test(directive))
  );
}

test('QA D1202: the app installs the focus rescue at start-up', () => {
  assert.ok(
    installsFocusRescue(read('app.config.ts')),
    'app.config.ts must install installFocusRescue',
  );
});

test('QA D1131 / D1208: every dialog directive keeps typed text on Escape', () => {
  for (const rel of DIALOG_DIRECTIVES) {
    assert.ok(keepsTypedText(read(rel)), `${rel} must use EscapeKeepsTypedText`);
  }
});

test('guards: the scans see the shapes they stop', () => {
  assert.equal(
    installsFocusRescue('provideAppInitializer(() => installDecimalCommaSupport(d)),'),
    false,
  );
  assert.equal(
    installsFocusRescue('provideAppInitializer(() => installFocusRescue(inject(DOCUMENT))),'),
    true,
  );
  const bare = `@HostListener('document:keydown', ['$event']) onDocumentKeydown() {}`;
  assert.equal(keepsTypedText(bare), false);
  const wired = `private readonly typedText = new EscapeKeepsTypedText(box, t);
    ngOnDestroy() { this.typedText.destroy(); }
    @HostListener('document:keydown', ['$event']) d(event) { if (this.typedText.intercept(event)) return; }`;
  assert.equal(keepsTypedText(wired), true);
});

// A search or picker box inside a dialog holds a query, not work: Escape there
// must close as before, so it carries `data-escape-discards` (or is
// type="search" / role="combobox"). 15 such boxes were marked in round 22.
const QUERY_HINT = /search|query|filter|recherch/i;
const MARKED = /type="?search|data-escape-discards|combobox/;
const NOT_TEXT = /type=["']?(?:checkbox|radio|hidden|date)/;

export function unmarkedQueryFields(html) {
  const tk = tags(html);
  const out = [];
  tk.forEach((t, i) => {
    if (t.type !== 'open' || !DIALOG_ATTRS.some((d) => t.attrs.has(d))) return;
    for (const x of tk.slice(i + 1, closeIndexOf(tk, i))) {
      if (x.type !== 'open' || x.name !== 'input') continue;
      const s = [...x.attrs].map(([k, v]) => `${k}=${v ?? ''}`).join(' ');
      if (QUERY_HINT.test(s) && !MARKED.test(s) && !NOT_TEXT.test(s)) out.push(x.line);
    }
  });
  return out;
}

test('QA D1131 class: a query box inside a dialog never asks before Escape', () => {
  const found = [];
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    for (const line of unmarkedQueryFields(text)) {
      found.push(`${relative(appDir, file).replaceAll('\\', '/')}:${line + (lineOffset ?? 0)}`);
    }
  }
  assert.deepEqual(found, [], 'mark the search box data-escape-discards');
  const html = '<div appModalDialog aria-label="x"><input id="patientSearch" type="text" /></div>';
  assert.deepEqual(unmarkedQueryFields(html), [1]);
  assert.deepEqual(unmarkedQueryFields(html.replace('<input', '<input data-escape-discards')), []);
});

// QA D1220 (round 23; the D1191 / D1195 focus sibling): a late focus return
// that asks "did the keyboard fall to the BODY?" never runs once the focus
// rescue has put focus on its stand-in (the row's cell, the table body), so
// Escape or "Discard" in a row editor left the keyboard on a TD / TBODY
// instead of the pencil. Every late return asks `focusFell()` (focus-rescue.ts:
// the body OR the rescue's stand-in). Rule: no portal `.ts` compares the
// focused element with `document.body` to decide whether to move focus,
// outside the rescue itself. Allowlist: focus-return.ts, which RECORDS the
// last focused element (it never moves focus).
const BODY_COMPARE = /\b(?:activeElement|active)\s*[!=]==?\s*(?:document|doc)\.body\b/;
const BODY_COMPARE_ALLOWED = new Map([
  ['shared/a11y/focus-rescue.ts', 'the rescue itself: it defines what "fell" means'],
  [
    'shared/a11y/focus-return.ts',
    'records the last focused element for an opener; never moves focus',
  ],
]);

export function comparesFocusWithBody(source) {
  return source
    .split('\n')
    .some((line) => BODY_COMPARE.test(line) && !/^\s*(?:\/\/|\*)/.test(line));
}

test('QA D1220: the scan sees a late return that yields to the rescue stand-in', () => {
  assert.equal(
    comparesFocusWithBody(
      `const active = document.activeElement;\nif (active !== null && active !== document.body) return;`,
    ),
    true,
  );
  assert.equal(comparesFocusWithBody(`if (!focusFell()) return;`), false);
});

test('QA D1220: every late focus return asks focusFell(), never "is focus on the body?"', () => {
  const offenders = [];
  for (const file of walk(appDir, ['.ts'])) {
    const rel = relative(appDir, file).split(sep).join('/');
    if (rel.endsWith('.spec.ts') || rel.startsWith('testing/') || BODY_COMPARE_ALLOWED.has(rel))
      continue;
    if (comparesFocusWithBody(readFileSync(file, 'utf8'))) offenders.push(rel);
  }
  assert.deepEqual(offenders, [], 'use focusFell() from shared/a11y/focus-rescue.ts');
  for (const rel of [
    'shared/a11y/inline-sub-form.directive.ts',
    'shared/a11y/focus-on-render.directive.ts',
    'shared/a11y/drawer-dialog.directive.ts',
  ]) {
    assert.match(read(rel), /focusFell\(\)/, `${rel} must ask focusFell()`);
  }
});
