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
