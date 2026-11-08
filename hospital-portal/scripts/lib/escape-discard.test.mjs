// QA D1285 guard (round 27; D1131 class): a dialog that swallows the Escape the
// reader confirmed with "Discard".
//
// Every dialog directive asks "Discard what you typed?" when Escape is pressed
// over typed text (shared/a11y/escape-keeps-typed-text.ts); "Discard" sends
// the Escape on so the dialog closes as it always did. The triage and nursing
// intake dialogs bound their Escape to a handler with a veto of its own —
// `if (!this.hasEntries()) this.dismissed.emit()` — so the confirmed Escape
// closed nothing, the text stayed, and every Escape after it was ignored.
//
// Rule: a method bound to a dialog's Escape (`(escaped)`, `(dialogDismiss)`,
// `(dialogClose)`, `(drawerClose)`, `(keydown.escape)`) that refuses to close
// while something is entered (it reads `hasEntries`, `isDirty`, `dirty`,
// `hasChanges`, `unsaved`, `hasTyped`) also reads `escapeDiscardConfirmed()`.
// Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';
import { memberBody } from './disabled-control-scan.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const ESCAPE_OUTPUTS = [
  '(escaped)',
  '(dialogDismiss)',
  '(dialogClose)',
  '(drawerClose)',
  '(keydown.escape)',
];
const VETO = /\b(?:hasEntries|isDirty|dirty|hasChanges|unsaved|hasTyped)\w*\b/;
const CONFIRMED = /\bescapeDiscardConfirmed\(/;

function handlerName(binding) {
  return /^\s*(?:this\.)?([A-Za-z_$][\w$]*)\s*\(/.exec(binding ?? '')?.[1] ?? null;
}

/** The Escape handlers of a template that veto on entries without the confirmed discard. */
export function escapeVetoFindings(html, componentTs = '') {
  const out = [];
  const seen = new Set();
  for (const t of tags(html)) {
    if (t.type !== 'open') continue;
    for (const output of ESCAPE_OUTPUTS) {
      const name = handlerName(t.attrs.get(output));
      if (!name || seen.has(name)) continue;
      seen.add(name);
      const body = memberBody(componentTs, name);
      if (VETO.test(body) && !CONFIRMED.test(body)) out.push({ line: t.line, handler: name });
    }
  }
  return out;
}

test('QA D1285: no dialog swallows the Escape its reader confirmed with "Discard"', () => {
  const found = [];
  let dialogs = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const tsFile = file.endsWith('.html') ? file.replace(/\.html$/, '.ts') : file;
    const componentTs = existsSync(tsFile) ? readFileSync(tsFile, 'utf8') : '';
    dialogs += tags(text).filter(
      (t) => t.type === 'open' && ESCAPE_OUTPUTS.some((o) => t.attrs.has(o)),
    ).length;
    for (const f of escapeVetoFindings(text, componentTs))
      found.push(`${rel}:${f.line + (lineOffset ?? 0)} ${f.handler}()`);
  }
  assert.ok(dialogs > 50, `every dialog must be read, saw ${dialogs}`);
  assert.deepEqual(
    found,
    [],
    'close when escapeDiscardConfirmed() (shared/a11y/escape-keeps-typed-text) is true',
  );
});

test('QA D1285 guard: the scan sees the triage shape and accepts the fixed one', () => {
  const html = '<div appModalDialog (escaped)="onEscape()"></div>';
  const broken = '  onEscape(): void {\n    if (!this.hasEntries()) this.dismissed.emit();\n  }\n';
  assert.equal(escapeVetoFindings(html, broken).length, 1);
  const fixed =
    '  onEscape(): void {\n    if (escapeDiscardConfirmed() || !this.hasEntries()) this.dismissed.emit();\n  }\n';
  assert.deepEqual(escapeVetoFindings(html, fixed), []);
  const plain = '  onEscape(): void {\n    this.dismissed.emit();\n  }\n';
  assert.deepEqual(escapeVetoFindings(html, plain), []);
});
