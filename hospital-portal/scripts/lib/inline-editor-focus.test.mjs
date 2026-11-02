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
//
// Round 22 (QA D1191, D1195): the pharmacy price editor SAVED the typed price
// on Escape — its `(change)` fired as Escape destroyed the focused input — and
// the invoice line editor dropped a typed price on Escape with no question.
// Two more rules for every row editor (any `@if (editingX() === …)` block):
//  - it never commits on `(change)`, `(blur)` or `(focusout)` (a field that
//    goes away fires them): a save is Enter or a button;
//  - a block holding a text field is an inline form (`appInlineSubForm`), so
//    Escape asks before dropping typed text.
// The billing pin is gone: the line editor is an appInlineSubForm row.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/**
 * `@if (editingX() === row.id)`, `=== row.key`, `=== 'section'`: a block keyed to
 * one item of a list (a row, a section edited in place).
 */
const ROW_EDITOR = /@if\s*\(\s*(editing\w*)\(\)\s*===\s*(?:[\w.]+|'[^']*')\s*\)\s*\{/g;

const PINS = new Map();

/** A commit wired to an event a destroyed field fires. */
const BLUR_COMMIT = /^\((?:change|blur|focusout)\)$/;

/** A handler that only stores the value (`x.set($event)`, `x = $event`) is not a commit. */
const STORE_ONLY = /^\s*[\w.]+(?:\.set\(|\s*=\s*)/;

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

/**
 * QA D1191 / D1195: the row-editor blocks of `html` that commit on a blur-type
 * event, or that hold a text field without being an inline form.
 */
export function unsafeRowEditors(html) {
  const out = [];
  for (const m of html.matchAll(ROW_EDITOR)) {
    const open = m.index + m[0].length - 1;
    const body = html.slice(open + 1, closeOfBrace(html, open));
    const bodyTags = tags(body).filter((t) => t.type === 'open');
    const line = html.slice(0, m.index).split('\n').length;
    for (const t of bodyTags) {
      for (const [key, value] of t.attrs) {
        if (BLUR_COMMIT.test(key) && !STORE_ONLY.test(value ?? '')) {
          out.push({ line, signal: m[1], rule: `commits on ${key}` });
        }
      }
    }
    const textField = bodyTags.some(
      (t) =>
        t.name === 'textarea' ||
        (t.name === 'input' &&
          /^["']?(?:text|number|email|tel|url)?$/.test(t.attrs.get('type') ?? '')),
    );
    const root = bodyTags[0];
    if (textField && !root?.attrs.has('appInlineSubForm')) {
      out.push({ line, signal: m[1], rule: 'a text field outside an appInlineSubForm root' });
    }
  }
  return out;
}

test('QA D1191 / D1195: no row editor saves on blur, and each one keeps Escape to itself', () => {
  const offenders = [];
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const f of unsafeRowEditors(text)) {
      offenders.push(`${rel}:${f.line + (lineOffset ?? 0)} ${f.signal}: ${f.rule}`);
    }
  }
  assert.deepEqual(
    offenders,
    [],
    'save on Enter or a button, and make the editor an appInlineSubForm (Escape asks before dropping text)',
  );
});

test('QA D1191 guard: the scan sees the round-22 inventory shape and the fixed one', () => {
  const broken = `@if (editingPriceId() === item.id) {
      <input type="number" appFocusOnRender (change)="savePrice(item, $any($event.target).value)" (keydown.escape)="editingPriceId.set(null)" />
    }`;
  assert.deepEqual(
    unsafeRowEditors(broken).map((f) => f.rule),
    ['commits on (change)', 'a text field outside an appInlineSubForm root'],
  );
  const fixed = `@if (editingPriceId() === item.id) {
      <span appInlineSubForm (dismissed)="editingPriceId.set(null)">
        <input type="number" appFocusOnRender (keydown.enter)="savePrice(item, p.value)" />
      </span>
    }`;
  assert.deepEqual(unsafeRowEditors(fixed), []);
  const section = `@if (editingSection() === 'medical') { <textarea (blur)="save()"></textarea> }`;
  assert.equal(unsafeRowEditors(section).length, 2);
});

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
