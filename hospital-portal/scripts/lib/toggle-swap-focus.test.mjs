// QA D2100 guard (round 59): a show/hide toggle written as two buttons.
//
// The patient header showed « Afficher le numéro complet » under
// `@if (canReveal() && !fullValue)` and « Masquer le numéro » under
// `@if (fullValue)`. Pressing the first destroyed the focused element, so the
// keyboard fell to the page <h1> and the reader had to tab back to hide the
// number again. The platform hospital-link « Activer / Désactiver » pair was
// the same shape (`@if (enabled) { <button> } @else { <button> }`).
//
// Rule, over every template of the portal (each `.html` and each inline
// `template:`): a branch holding only a <button> must not be followed by a
// sibling branch holding only a <button> when the second is the first's
// negation — an `@else`, or a second `@if` whose condition negates a term of
// the first. Write ONE button whose label (and class) switch:
// `{{ (on ? 'X.HIDE' : 'X.SHOW') | translate }}`. Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const COND = String.raw`\(((?:[^()]|\([^()]*\))*)\)`;
const BUTTON_ONLY = String.raw`\{\s*<button\b[^>]*>(?:(?!<\/button>)[\s\S])*<\/button>\s*\}`;
const IF_ELSE = new RegExp(
  String.raw`@if\s*${COND}\s*${BUTTON_ONLY}\s*@else\s*${BUTTON_ONLY}`,
  'g',
);
const IF_IF = new RegExp(
  String.raw`@if\s*${COND}\s*${BUTTON_ONLY}\s*@if\s*${COND}\s*${BUTTON_ONLY}`,
  'g',
);

const terms = (cond) => new Set(cond.match(/[\w.]+(?:\(\))?/g) ?? []);
const negated = (cond) => new Set([...cond.matchAll(/!\s*([\w.]+(?:\(\))?)/g)].map((m) => m[1]));
const positive = (cond) => {
  const neg = negated(cond);
  return new Set([...terms(cond)].filter((t) => !neg.has(t)));
};
const overlaps = (a, b) => [...a].some((t) => b.has(t));
const opposes = (a, b) => overlaps(negated(a), positive(b)) || overlaps(negated(b), positive(a));

/**
 * Pairs that are not toggles: the click never flips the condition, so the
 * focused button is never swapped under the reader. Each entry says why.
 */
const NOT_TOGGLES = new Map([
  // The bulk-shift modal opens in one mode or the other; submitting never switches it.
  ['scheduling/scheduling.html|singleShiftMode()', 'modal mode, not flipped by the click'],
]);

/** The swapped button pairs of a template: `{ line, condition }`. */
export function swappedToggles(html) {
  const out = [];
  const line = (index) => html.slice(0, index).split('\n').length;
  for (const m of html.matchAll(IF_ELSE)) out.push({ line: line(m.index), condition: m[1].trim() });
  for (const m of html.matchAll(IF_IF)) {
    const [a, b] = [m[1], m[2]];
    if (opposes(a, b)) {
      out.push({ line: line(m.index), condition: `${a.trim()} / ${b.trim()}` });
    }
  }
  return out;
}

test('QA D2100: no show/hide toggle swaps one button for another in any template', () => {
  const found = [];
  let templates = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    templates++;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const s of swappedToggles(text)) {
      if (NOT_TOGGLES.has(`${rel}|${s.condition}`)) continue;
      found.push(`${rel}:${s.line + (lineOffset ?? 0)} ${s.condition}`);
    }
  }
  assert.ok(templates > 200, `every template must be read, saw ${templates}`);
  assert.deepEqual(found, [], 'use one button whose label switches, so focus survives');
});

test('QA D2100 guard: the scan sees the IU pair and the link pair, not one switching button', () => {
  const iu = `@if (canReveal() && !fullValue) {
      <button type="button" (click)="reveal()">{{ 'NATIONAL_IU.SHOW_FULL' | translate }}</button>
    }
    @if (fullValue) {
      <button type="button" (click)="clear()">{{ 'NATIONAL_IU.HIDE' | translate }}</button>
    }`;
  assert.equal(swappedToggles(iu).length, 1);
  const link = `@if (row.link.enabled) {
      <button (click)="set(row, false)">{{ 'PLATFORM.DISABLE_LINK' | translate }}</button>
    } @else {
      <button (click)="set(row, true)">{{ 'PLATFORM.ENABLE_LINK' | translate }}</button>
    }`;
  assert.equal(swappedToggles(link).length, 1);
  const one = `@if (canReveal() || fullValue) {
      <button (click)="toggle()">{{ (fullValue ? 'A.HIDE' : 'A.SHOW') | translate }}</button>
    }`;
  assert.deepEqual(swappedToggles(one), []);
  // Two unrelated buttons side by side are not a toggle.
  const unrelated = `@if (canEdit()) { <button>Edit</button> } @if (canPrint()) { <button>Print</button> }`;
  assert.deepEqual(swappedToggles(unrelated), []);
  // A shared negated guard is not an opposition.
  const shared = `@if (canEdit() && !closed) { <button>Edit</button> } @if (canDelete() && !closed) { <button>Delete</button> }`;
  assert.deepEqual(swappedToggles(shared), []);
});
