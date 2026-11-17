// QA D1259 guard (round 26; D1251 sibling): Escape in a field whose
// suggestion list is open closed the whole dialog.
//
// The reception waitlist's "Prestataire préféré" combobox closed its list on
// Escape but let the key go on: the dialog closed with the query, or asked
// "Abandonner le texte saisi ?" over the reason already typed. Every other
// hand-written suggestion list (the patient search of the prescription, lab,
// consultation, referral, admission and treatment-plan dialogs, the shared
// patient picker, the walk-in and recall pickers) had no Escape at all, so the
// dialog closed under the open list.
//
// Rule, over every template (each `.html` and each inline `template:`): a
// suggestion list — `role="listbox"`, or a class that names one
// (`picker-dropdown`, `autocomplete-list`, `suggestions…`, `…typeahead__list`,
// `…__suggestions`, `…__popup`, `picker-results`; a list that is always
// shown under a filter, such as the order-set picker's, is not a popup) — belongs to the text field
// written before it, and that field (or the wrapper around both) handles
// Escape: `[appListEscape]` (shared/a11y/list-escape.directive.ts) or a
// `(keydown)` handler (comboboxKey, which now stops the key at an open list).
// The handlers themselves are proven by list-escape.directive.spec.ts and
// combobox-keys.spec.ts. Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';
import { closeIndexOf } from './dialog-scan.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const LIST_CLASS =
  /(?:^|\s)(?:picker-dropdown|autocomplete-list|suggestions(?:-dropdown)?|picker-results|[\w-]+__(?:suggestions|popup)|[\w-]*typeahead__list)(?:\s|$)/;
const FIELD = new Set(['input', 'textarea']);
const ESCAPE_HANDLER = /^(?:\[appListEscape\]|\(keydown\)|\(keydown\.esc(?:ape)?\))$/i;

const unquote = (v) => (v ?? '').replace(/^["']|["']$/g, '').trim();

function isList(tag) {
  if (unquote(tag.attrs.get('role')) === 'listbox') return true;
  return LIST_CLASS.test(unquote(tag.attrs.get('class')));
}

function handlesEscape(tag) {
  return [...tag.attrs.keys()].some((a) => ESCAPE_HANDLER.test(a));
}

/** A dialog, its backdrop, or a form: a wrapper whose Escape closes more than the list. */
function isDialogLike(tag) {
  if (tag.name === 'form' || tag.name === 'dialog') return true;
  if (['appModalDialog', 'appDialogA11y', 'appDialogFocus'].some((a) => tag.attrs.has(a)))
    return true;
  if (/^(?:dialog|alertdialog)$/.test(unquote(tag.attrs.get('role')))) return true;
  return /(?:^|[\s-])(?:modal|backdrop|overlay|dialog)(?:[\s-]|$)/.test(
    unquote(tag.attrs.get('class')),
  );
}

/** The suggestion lists of a template whose field lets Escape through: `{ line, field }`. */
export function unhandledListEscapes(html) {
  const tokens = tags(html);
  const out = [];
  tokens.forEach((t, i) => {
    if (t.type !== 'open' || !isList(t)) return;
    let k = i - 1;
    while (k >= 0 && !(tokens[k].type === 'open' && FIELD.has(tokens[k].name))) k--;
    if (k < 0) return;
    const field = tokens[k];
    if (unquote(field.attrs.get('type')) === 'checkbox') return;
    if (handlesEscape(field)) return;
    // A picker's own wrapper around both the field and its list (the hospital
    // typeahead's `(keydown)` on its root) handles it too — never a dialog or
    // its backdrop: their Escape is the very handler that closed the dialog.
    for (let w = k - 1; w >= 0; w--) {
      const tag = tokens[w];
      if (tag.type !== 'open' || closeIndexOf(tokens, w) < i) continue;
      if (isDialogLike(tag)) break;
      if (tag.attrs.has('(keydown)')) return;
    }
    out.push({ line: t.line, field: unquote(field.attrs.get('id')) || `line ${field.line}` });
  });
  return out;
}

test('QA D1259: Escape over an open suggestion list closes the list, not the dialog', () => {
  const found = [];
  let lists = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    lists += tags(text).filter((t) => t.type === 'open' && isList(t)).length;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const f of unhandledListEscapes(text))
      found.push(`${rel}:${f.line + (lineOffset ?? 0)} (${f.field})`);
  }
  assert.ok(lists >= 15, `every suggestion list must be read, saw ${lists}`);
  assert.deepEqual(
    found,
    [],
    'put [appListEscape]="<list open>" (listEscape)="<close it>" on the field (ListEscapeDirective)',
  );
});

test('QA D1259 guard: the scan sees the round-26 shapes', () => {
  const broken = `<div class="modal"><input id="p" (input)="q($event)" />
      @if (open()) { <ul class="autocomplete-list"><li>A</li></ul> }</div>`;
  assert.deepEqual(
    unhandledListEscapes(broken).map((f) => f.field),
    ['p'],
  );
  const fixed = broken.replace(
    '<input id="p"',
    '<input id="p" [appListEscape]="open()" (listEscape)="open.set(false)"',
  );
  assert.deepEqual(unhandledListEscapes(fixed), []);
  // An ARIA combobox (comboboxKey) and a wrapper handler both count.
  const combo = `<input role="combobox" (keydown)="onKey($event)" /><ul role="listbox"></ul>`;
  assert.deepEqual(unhandledListEscapes(combo), []);
  const wrapped = `<div (keydown)="onKey($event)"><input /><ul class="x__popup"></ul></div>`;
  assert.deepEqual(unhandledListEscapes(wrapped), []);
  assert.equal(unhandledListEscapes('<input /><div class="picker-dropdown"></div>').length, 1);
  // The backdrop's own Escape is what closed the dialog: it never counts.
  const backdrop = `<div class="modal-backdrop" (keydown.escape)="close()" (keydown)="k($event)">
      <div class="modal"><input id="p" /><ul class="picker-dropdown"></ul></div></div>`;
  assert.deepEqual(
    unhandledListEscapes(backdrop).map((f) => f.field),
    ['p'],
  );
  // The directive the rule asks for exists and stops the key at an open list.
  const directive = readFileSync(
    join(appDir, 'shared', 'a11y', 'list-escape.directive.ts'),
    'utf8',
  );
  assert.match(directive, /selector: '\[appListEscape\]'/);
  assert.match(directive, /stopPropagation\(\)/);
});
