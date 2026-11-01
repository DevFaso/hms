// QA D1201 guard (round 22): a reason asked through the browser's own prompt.
//
// Transfer cancel and precaution lift on the bed board asked "Pourquoi … ?"
// with window.prompt: a blank answer silently did nothing, the question was
// in the browser's chrome (no label, no refusal said, no focus management, no
// Escape rule), and four more screens did the same (transfusion cancel and
// discard, pharmacy claim reject, order-set deactivation).
//
// Rule: no portal source calls `prompt(` — bare, `window.prompt(`,
// `globalThis.prompt(` or `self.prompt(`. A reason is asked with the shared
// `<app-reason-dialog>` (shared/reason-dialog), which requires it, says why a
// blank one is refused, and keeps typed text on Escape. Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';
import { endOfLiteralOrComment } from './js-scan.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');

/** `prompt(` called as the global function (a method `.prompt(` of something else is not it). */
const NATIVE_PROMPT = /(?:\b(?:window|globalThis|self)\s*\.\s*|(?<![.\w$]))prompt\s*\(/g;

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
  return [...text.matchAll(NATIVE_PROMPT)].map((m) => text.slice(0, m.index).split('\n').length);
}

test('QA D1201: no portal source asks a reason with the browser prompt', () => {
  const found = [];
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const line of nativePrompts(readFileSync(file, 'utf8'))) found.push(`${rel}:${line}`);
  }
  assert.deepEqual(found, [], 'ask the reason with <app-reason-dialog>, not window.prompt');
});

test('QA D1201 guard: the scan sees every way of calling the prompt, and nothing else', () => {
  assert.deepEqual(nativePrompts("const r = window.prompt(this.t('X'));"), [1]);
  assert.deepEqual(nativePrompts("const r = globalThis.prompt('x');"), [1]);
  assert.deepEqual(nativePrompts("\nconst r = prompt('x');"), [2]);
  assert.deepEqual(nativePrompts('this.install.prompt();'), []);
  assert.deepEqual(nativePrompts("// window.prompt('x') was used here"), []);
  assert.deepEqual(nativePrompts("const s = 'window.prompt(';"), []);
  assert.deepEqual(nativePrompts('showPrompt(x);'), []);
});
