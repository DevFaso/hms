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
import { existsSync, readFileSync } from 'node:fs';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';
import { closeIndexOf } from './dialog-scan.mjs';

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

// ── QA D1307 (round 28; the D1175 sibling the ROW_EDITOR pattern cannot see) ──
// The dispensing history's cancel button opened the reason form ABOVE the
// table (`@if (cancelWitnessFor()) { <textarea> }`) and kept the focus on the
// row: a keyboard or screen-reader user never reached the reason. The
// round-21 rule only reads blocks keyed `=== row.id` in the row itself; a
// panel opened BY a row action and rendered elsewhere is keyed on a plain
// signal or property. Rule: every `@if (x())` / `@if (x)` block holding a
// field, outside any dialog, whose condition is set by a handler bound to a
// `(click)` inside an `@for` (directly, `x.set(…)` / `x = …`, or through a
// component method that does it), takes the keyboard: its root is an
// `appInlineSubForm`, a field carries `appFocusOnRender`, or it is a dialog.
const FOR_BLOCK = /@for\s*\(/g;
const PLAIN_IF = /@if\s*\(\s*(\w+)(?:\(\))?\s*(?:;\s*as\s+\w+\s*)?\)\s*\{/g;
const TAKES_KEYBOARD = [
  'appInlineSubForm',
  'appFocusOnRender',
  'appModalDialog',
  'appDialogA11y',
  'appDialogFocus',
];
const DIALOG_DIRECTIVE = ['appModalDialog', 'appDialogA11y', 'appDialogFocus'];

/** QA D1307 pins: a row-opened panel that keeps focus in its list by design. */
const ROW_PANEL_PINS = new Map([
  [
    'nurse-station/emar/emar.component.html activeTask',
    'master-detail: choosing a task in the queue shows its detail beside the list; focus stays in the queue (the D1283 decision, re-tested FIXED in round 28)',
  ],
  [
    'bed-board/bed-board.html board',
    'not a panel: the whole census board, reloaded after a row action (found by the round-29 transitive follow); its filters were on the page before the click',
  ],
]);

function isDialogTag(t) {
  return (
    t.name === 'dialog' ||
    /dialog/.test(t.attrs.get('role') ?? '') ||
    DIALOG_DIRECTIVE.some((a) => t.attrs.has(a))
  );
}

/** Lines of `html` inside a dialog or an inline form (they own the keyboard): `[first, last]` pairs. */
function keyboardOwnerLines(html) {
  const tk = tags(html);
  const out = [];
  tk.forEach((t, i) => {
    if (t.type === 'open' && (isDialogTag(t) || t.attrs.has('appInlineSubForm'))) {
      out.push([t.line, tk[closeIndexOf(tk, i)].line]);
    }
  });
  return out;
}

/** The names a row action sets: `x.set(` / `x =` in the binding or in the method it calls. */
function namesSetByRowActions(html, ts) {
  const names = new Set();
  const calls = new Set();
  for (const m of html.matchAll(FOR_BLOCK)) {
    const open = html.indexOf('{', m.index);
    const body = html.slice(open + 1, closeOfBrace(html, open));
    for (const c of body.matchAll(/\(click\)="([^"]*)"/g)) {
      for (const s of c[1].matchAll(/(\w+)(?:\.set\(|\s*=(?!=))/g)) names.add(s[1]);
      for (const f of c[1].matchAll(/(?:^|[;\s(!])(\w+)\(/g)) calls.add(f[1]);
    }
  }
  // QA D1335 (round 29): the dispensing queue's "Dispenser" opened its form
  // three calls away (selectPrescription → the claim's answer → openFormFor →
  // openForm sets showForm), and the rule read only the method the click
  // names. Every method reached through `this.fn(` is followed (depth 5).
  const seen = new Set();
  let frontier = [...calls];
  for (let depth = 0; depth < 5 && frontier.length > 0; depth++) {
    const next = [];
    for (const fn of frontier) {
      if (seen.has(fn)) continue;
      seen.add(fn);
      const body = methodBody(ts, fn);
      if (body === null) continue;
      for (const s of body.matchAll(/this\.(\w+)(?:\.set\(|\s*=(?!=))/g)) names.add(s[1]);
      for (const c of body.matchAll(/this\.(\w+)\(/g)) next.push(c[1]);
    }
    frontier = next;
  }
  return names;
}

/** The body of method `fn` in `ts`, or null. */
function methodBody(ts, fn) {
  const head = new RegExp(
    String.raw`\n\s*(?:public |private |protected )?(?:async )?${fn}\([^)]*\)[^{;]*\{`,
  );
  const m = head.exec(ts);
  if (!m) return null;
  const open = m.index + m[0].length - 1;
  return ts.slice(open, closeOfBrace(ts, open));
}

/** QA D1307: the row-opened panels of `html` (with its component source `ts`) that leave focus behind. */
export function rowPanelsWithoutFocus(html, ts) {
  const opened = namesSetByRowActions(html, ts);
  const inDialog = keyboardOwnerLines(html);
  const out = [];
  let reportedUntil = -1;
  for (const m of html.matchAll(PLAIN_IF)) {
    if (m.index < reportedUntil || !opened.has(m[1])) continue;
    const line = html.slice(0, m.index).split('\n').length;
    if (inDialog.some(([a, b]) => line >= a && line <= b)) continue;
    const open = m.index + m[0].length - 1;
    const end = closeOfBrace(html, open);
    const bodyTags = tags(html.slice(open + 1, end)).filter((t) => t.type === 'open');
    const field = bodyTags.some(
      (t) =>
        ['select', 'textarea'].includes(t.name) ||
        (t.name === 'input' && !/hidden/.test(t.attrs.get('type') ?? '')),
    );
    if (!field) continue;
    if (bodyTags.some((t) => isDialogTag(t) || TAKES_KEYBOARD.some((a) => t.attrs.has(a)))) {
      continue;
    }
    reportedUntil = end;
    out.push({ line, signal: m[1] });
  }
  return out;
}

function componentSourceOf(file) {
  if (file.endsWith('.ts')) return readFileSync(file, 'utf8');
  const ts = file.replace(/\.html$/, '.ts');
  return existsSync(ts) ? readFileSync(ts, 'utf8') : '';
}

test('QA D1307: a form opened by a row action takes the keyboard', () => {
  const found = new Map();
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const f of rowPanelsWithoutFocus(text, componentSourceOf(file))) {
      found.set(`${rel} ${f.signal}`, `${rel}:${f.line + (lineOffset ?? 0)} ${f.signal}`);
    }
  }
  const unpinned = [...found.entries()].filter(([k]) => !ROW_PANEL_PINS.has(k)).map(([, v]) => v);
  assert.deepEqual(
    unpinned,
    [],
    'make the panel an appInlineSubForm (it takes the keyboard, keeps Escape, returns focus)',
  );
  const stale = [...ROW_PANEL_PINS.keys()].filter((k) => !found.has(k));
  assert.deepEqual(stale, [], 'a pinned panel no longer offends: delete its pin');
});

test('QA D1307 guard: the scan sees the round-28 dispensing shape and accepts the fixed one', () => {
  const ts = `\n  cancelDispense(id: string): void {\n    this.cancelWitnessFor.set(id);\n  }\n`;
  const broken = `@if (cancelWitnessFor()) {
      <div class="form-row"><textarea id="reason"></textarea></div>
    }
    @for (d of rows(); track d.id) {
      <tr><td><button (click)="cancelDispense(d.id)">x</button></td></tr>
    }`;
  assert.deepEqual(
    rowPanelsWithoutFocus(broken, ts).map((f) => f.signal),
    ['cancelWitnessFor'],
  );
  const fixed = broken.replace('<div class="form-row">', '<div class="form-row" appInlineSubForm>');
  assert.deepEqual(rowPanelsWithoutFocus(fixed, ts), []);
  // Opened by a button outside any list: a disclosure, not a row action.
  const toolbar = broken.replace(/@for[\s\S]*$/, '<button (click)="cancelDispense(1)">x</button>');
  assert.deepEqual(rowPanelsWithoutFocus(toolbar, ts), []);
  // Inline in the binding, and a plain property.
  const inline = `@if (editing) { <select></select> }
    @for (r of rows; track r.id) { <button (click)="editing = r">e</button> }`;
  assert.deepEqual(
    rowPanelsWithoutFocus(inline, '').map((f) => f.signal),
    ['editing'],
  );
  // Inside a dialog: the dialog takes the keyboard.
  const inDialog = `<div appModalDialog aria-label="x">${broken}</div>`;
  assert.deepEqual(rowPanelsWithoutFocus(inDialog, ts), []);
});
