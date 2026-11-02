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

// QA D1260 (round 26, D1004 sibling): a dialog opened from inside another one
// that closes in the same click (login: "Nom d'utilisateur oublié ?" then
// "Réinitialiser le mot de passe") gave focus to the page root on close: its
// opener had left with the first dialog. focus-return.ts now chains each
// dialog's opener to the dialog it sat in — which works only if every dialog
// directive registers itself when it captures its opener.
test('QA D1260: every dialog directive registers its box with its opener', () => {
  const missing = DIALOG_DIRECTIVES.filter(
    (rel) => !/captureOpener\(\s*this\.host\.nativeElement/.test(read(rel)),
  );
  assert.deepEqual(missing, [], 'call captureOpener(this.host.nativeElement as HTMLElement)');
  const focusReturn = read('shared/a11y/focus-return.ts');
  assert.match(focusReturn, /fallbackOf\.get\(target\)/, 'restoreFocus walks the opener chain');
});

// ── QA D1283 / D1284 (round 27; D1004 / D1260 NOT_FIXED, the D1270 sibling) ──
// After a SUCCESSFUL dialog action the screen reloaded its list under a
// loading flag; the row button that opened the dialog left with the table and
// focus ended on the page component (APP-NURSE-STATION, APP-LAB, the eMAR
// SECTION). Escape passed because a cancel reloads nothing. One choke point
// for every dialog of the portal: each dialog goes through one of the three
// dialog directives; each gives focus back through focus-return.ts
// `restoreFocus`; an opener that is not on the page is handed to the focus
// rescue (`awaitReturn`), which gives focus to its twin when the list is back
// and never stands in on a component host. Behaviour:
// shared/a11y/focus-after-success.spec.ts (each directive) and the nurse
// station, eMAR and lab specs (the reported screens).
const DIALOG_ROLE = /^(?:dialog|alertdialog)$/;

/** The dialogs of a template that do not go through one of the three directives: `[line]`. */
export function dialogsOutsideTheDirectives(html) {
  const out = [];
  for (const t of tags(html)) {
    if (t.type !== 'open') continue;
    const role = (t.attrs.get('role') ?? '').replaceAll(/["']/g, '');
    const isDialog =
      t.name === 'dialog' || DIALOG_ROLE.test(role) || DIALOG_ATTRS.some((d) => t.attrs.has(d));
    if (isDialog && !DIALOG_ATTRS.some((d) => t.attrs.has(d))) out.push(t.line);
  }
  return out;
}

export function restoresThroughTheChokePoint(directive) {
  return /ngOnDestroy\(\)[^{]*\{[\s\S]*?restoreFocus\(/.test(directive);
}

test('QA D1283: every dialog of the portal goes through a directive that restores focus', () => {
  const outside = [];
  let dialogs = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).split(sep).join('/');
    dialogs += tags(text).filter(
      (t) =>
        t.type === 'open' &&
        (t.name === 'dialog' ||
          DIALOG_ROLE.test((t.attrs.get('role') ?? '').replaceAll(/["']/g, '')) ||
          DIALOG_ATTRS.some((d) => t.attrs.has(d))),
    ).length;
    for (const line of dialogsOutsideTheDirectives(text))
      outside.push(`${rel}:${line + (lineOffset ?? 0)}`);
  }
  assert.ok(dialogs >= 250, `every dialog must be read, saw ${dialogs}`);
  assert.deepEqual(outside, [], 'put appModalDialog (or appDialogA11y / appDialogFocus) on it');
  for (const rel of DIALOG_DIRECTIVES) {
    assert.ok(restoresThroughTheChokePoint(read(rel)), `${rel} must restoreFocus on destroy`);
  }
});

test('QA D1283: an opener gone with its list is awaited, and the page host never stands in', () => {
  const ret = read('shared/a11y/focus-return.ts');
  const body = /export function restoreFocus\([\s\S]*?\n\}/.exec(ret)?.[0] ?? '';
  assert.match(body, /awaitReturn\(/, 'restoreFocus hands an absent opener to the rescue');
  assert.match(ret, /keyOf\.set\(opener, focusKeyOf\(opener\)\)/, 'the key is taken on open');
  const rescue = read('shared/a11y/focus-rescue.ts');
  const fn = /export function rescueFocus\([\s\S]*?\n\}/.exec(rescue)?.[0] ?? '';
  assert.match(fn, /returnToTwin\(doc\)/, 'the twin is tried before any stand-in');
  assert.match(fn, /focusPlace\(standInFor\(place\)\)/, 'a component host never stands in');
  assert.ok(
    fn.indexOf('returnToTwin(doc)') < fn.indexOf('focusPlace(standInFor(place))'),
    'the twin is tried first',
  );
});

test('QA D1283 guard: the scans see a dialog outside the directives and a bare destroy', () => {
  assert.deepEqual(dialogsOutsideTheDirectives('<div role="dialog" aria-label="x"></div>'), [1]);
  assert.deepEqual(
    dialogsOutsideTheDirectives('<div role="dialog" appModalDialog aria-label="x"></div>'),
    [],
  );
  assert.equal(
    restoresThroughTheChokePoint('ngOnDestroy(): void { this.opener?.focus(); }'),
    false,
  );
  assert.equal(
    restoresThroughTheChokePoint('ngOnDestroy(): void { this.x(); restoreFocus(this.opener); }'),
    true,
  );
});
