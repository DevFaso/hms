// QA D1175 / D1144 guard (round 21): a row editor that replaces the control
// that opened it takes the keyboard.
//
// The pharmacy inventory's "Modifier le prix unitaire" pencil was swapped for
// a price input: the focused pencil was destroyed, the keyboard fell to the
// page body and the next Tab skipped the input. The same shape — a block
// keyed to one row, `@if (editingX() === row.id) { <field> }` — is how every
// in-place row editor is built (a price, a role, an invoice line).
//
// Rule: the first field (input / select / textarea) of every such block
// carries `appFocusOnRender` (shared/a11y/focus-on-render.directive.ts), or
// the block's root is an inline form (`appInlineSubForm`). Known sites in
// another branch's files are pinned with their reason; a pin whose site is
// fixed fails, so the list only shrinks.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/** `@if (somethingEditing…() === row.id)`: a block keyed to one row of a list. */
const ROW_EDITOR = /@if\s*\(\s*(editing\w*)\(\)\s*===\s*[\w.]+\.id\s*\)\s*\{/g;

const PINS = new Map([
  [
    'billing/billing.html',
    'QA D1175 class (round 21): the invoice line edited in place spans two table cells; focus-in and focus-return are hand-written in billing.ts (startEditLine / onLineEditEscape) and proved by the billing.spec.ts DOM spec; delete when the row moves onto appFocusOnRender',
  ],
]);

function closeOfBrace(html, open) {
  let depth = 0;
  for (let i = open; i < html.length; i++) {
    if (html[i] === '{') depth++;
    else if (html[i] === '}' && --depth === 0) return i;
  }
  return html.length;
}

/** The row-editor blocks of `html` whose first field does not take the keyboard. */
export function unfocusedRowEditors(html) {
  const out = [];
  for (const m of html.matchAll(ROW_EDITOR)) {
    const open = m.index + m[0].length - 1;
    const body = html.slice(open + 1, closeOfBrace(html, open));
    const bodyTags = tags(body).filter((t) => t.type === 'open');
    const field = bodyTags.find(
      (t) =>
        ['select', 'textarea'].includes(t.name) ||
        (t.name === 'input' && !/^["']?hidden/.test(t.attrs.get('type') ?? '')),
    );
    if (!field) continue;
    const root = bodyTags[0];
    if (field.attrs.has('appFocusOnRender') || root?.attrs.has('appInlineSubForm')) continue;
    out.push({ line: html.slice(0, m.index).split('\n').length, signal: m[1] });
  }
  return out;
}

test('QA D1175: every in-place row editor takes the keyboard from the control it replaced', () => {
  const offenders = new Map();
  let editors = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    editors += [...text.matchAll(ROW_EDITOR)].length;
    for (const f of unfocusedRowEditors(text)) {
      const list = offenders.get(rel) ?? [];
      list.push(`${rel}:${f.line + (lineOffset ?? 0)} ${f.signal}`);
      offenders.set(rel, list);
    }
  }
  assert.ok(editors >= 4, `the scan must see the row editors, saw ${editors}`);
  const unpinned = [...offenders.entries()].filter(([rel]) => !PINS.has(rel)).flatMap(([, l]) => l);
  assert.deepEqual(unpinned, [], 'add appFocusOnRender to the editor field');
  const stale = [...PINS.keys()].filter((rel) => !offenders.has(rel));
  assert.deepEqual(stale, [], 'a pinned file no longer offends: delete its pin');
});

test('QA D1175 guard: the scan sees the inventory shape and accepts the fixed one', () => {
  const broken = `@if (editingPriceId() === item.id) {
      <input type="number" class="price-input" (change)="savePrice(item, $any($event.target).value)" />
    } @else {
      <button (click)="editingPriceId.set(item.id)">edit</button>
    }`;
  assert.equal(unfocusedRowEditors(broken).length, 1);
  const fixed = broken.replace('class="price-input"', 'class="price-input" appFocusOnRender');
  assert.deepEqual(unfocusedRowEditors(fixed), []);
  const display = `@if (editingId() === member.id) { <span>{{ member.role }}</span> }`;
  assert.deepEqual(unfocusedRowEditors(display), []);
});
