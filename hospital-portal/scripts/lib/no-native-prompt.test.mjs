// QA D1201 guard (round 22): a reason asked through the browser's own prompt.
// QA D1229 (round 24): extended to the browser's confirm() and alert(). Ten
// screens still asked "are you sure?" with window.confirm (patient document
// delete, imaging report signing, death record, admin deletes, language
// reload): a question in the browser's chrome and language, with no keyboard
// contract and no focus return. They ask through ConfirmService
// (shared/confirm-dialog), which opens the shared <app-confirm-dialog>.
//
// Transfer cancel and precaution lift on the bed board asked "Pourquoi … ?"
// with window.prompt: a blank answer silently did nothing, the question was
// in the browser's chrome (no label, no refusal said, no focus management, no
// Escape rule), and four more screens did the same (transfusion cancel and
// discard, pharmacy claim reject, order-set deactivation).
//
// Rule: no portal source calls `prompt(`, `confirm(` or `alert(` — bare,
// `window.…(`, `globalThis.…(` or `self.…(`. A reason is asked with the shared
// `<app-reason-dialog>` (shared/reason-dialog), which requires it, says why a
// blank one is refused, and keeps typed text on Escape; a yes/no question with
// ConfirmService.ask(). A method DECLARED with one of those names (a
// component's own `confirm(): void {`) is not a call. Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';
import { endOfLiteralOrComment } from './js-scan.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');

/** The browser's own dialogs, called as the global function (a method `.confirm(` of something else is not it). */
const NATIVE_PROMPT =
  /(?:\b(?:window|globalThis|self)\s*\.\s*|(?<![.\w$]))(?:prompt|confirm|alert)\s*\(/g;

/** After the call's closing parenthesis, `{` or `:` (a return type) means a method declaration. */
function isDeclaration(text, openParen) {
  let depth = 0;
  for (let i = openParen; i < text.length; i++) {
    if (text[i] === '(') depth++;
    else if (text[i] === ')' && --depth === 0) return /^\s*[:{]/.test(text.slice(i + 1, i + 40));
  }
  return false;
}

/** The source with comments and string contents blanked, so a doc example never counts. */
function code(source) {
  let out = '';
  for (let i = 0; i < source.length; i++) {
    const end = endOfLiteralOrComment(source, i);
    if (end >= 0) {
      out += source.slice(i, end + 1).replace(/[^\n]/g, ' ');
      i = end;
    } else {
      out += source[i];
    }
  }
  return out;
}

export function nativePrompts(source) {
  const text = code(source);
  return [...text.matchAll(NATIVE_PROMPT)]
    .filter((m) => !isDeclaration(text, m.index + m[0].length - 1))
    .map((m) => text.slice(0, m.index).split('\n').length);
}

test('QA D1201: no portal source asks a reason with the browser prompt', () => {
  const found = [];
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const line of nativePrompts(readFileSync(file, 'utf8'))) found.push(`${rel}:${line}`);
  }
  assert.deepEqual(
    found,
    [],
    'ask a reason with <app-reason-dialog> and a yes/no with ConfirmService.ask(), never the browser',
  );
});

test('QA D1201 guard: the scan sees every way of calling the prompt, and nothing else', () => {
  assert.deepEqual(nativePrompts("const r = window.prompt(this.t('X'));"), [1]);
  assert.deepEqual(nativePrompts("const r = globalThis.prompt('x');"), [1]);
  assert.deepEqual(nativePrompts("\nconst r = prompt('x');"), [2]);
  assert.deepEqual(nativePrompts('this.install.prompt();'), []);
  assert.deepEqual(nativePrompts("// window.prompt('x') was used here"), []);
  assert.deepEqual(nativePrompts("const s = 'window.prompt(';"), []);
  assert.deepEqual(nativePrompts('showPrompt(x);'), []);
  // QA D1229: confirm() and alert() too.
  assert.deepEqual(nativePrompts("if (!confirm(this.t.instant('X'))) return;"), [1]);
  assert.deepEqual(nativePrompts("if (!window.confirm(this.t.instant('X'))) return;"), [1]);
  assert.deepEqual(nativePrompts("alert('x');"), [1]);
  assert.deepEqual(nativePrompts('  confirm(): void {'), [], 'a method declaration is not a call');
  assert.deepEqual(nativePrompts('  protected confirm(): void {'), []);
  assert.deepEqual(nativePrompts('this.confirm.ask({});'), []);
  assert.deepEqual(nativePrompts('(click)="confirm()"'), [], 'a string is never code');
});
