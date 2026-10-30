// QA D1023 (class guard, WCAG 2.1.1): nothing is done by mouse drag alone.
// The front desk could mark a visit "left without being seen" only by dragging
// its card onto a column; a keyboard user had no way at all. Every element
// that can be dragged (a CDK `cdkDrag`, or a native `(dragstart)`) must carry
// a keyboard handler on the same element: Alt+Arrow keys (`(keydown.alt.…)`)
// or a `(keydown)` handler that reads them (the shell's nav reorder).
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { loadTemplates } from './template-gate.mjs';

/** The opening tags of every draggable element of a template. */
export function draggableTagsWithoutKeyboard(html) {
  const out = [];
  for (const m of html.matchAll(/<[a-zA-Z][\w-]*\b[^>]*>/g)) {
    const tag = m[0];
    const draggable = /\scdkDrag(?=[\s>])/.test(tag) || /\(dragstart\)=/.test(tag);
    if (!draggable) continue;
    if (/\(keydown(?:\.alt\.[a-z]+)?\)=/.test(tag)) continue;
    out.push(tag.replace(/\s+/g, ' ').slice(0, 120));
  }
  return out;
}

test('QA D1023: every draggable element has a keyboard path', () => {
  const templates = loadTemplates();
  const offenders = [];
  let draggables = 0;
  for (const { rel, text } of templates) {
    draggables += (text.match(/\scdkDrag(?=[\s>])|\(dragstart\)=/g) ?? []).length;
    for (const tag of draggableTagsWithoutKeyboard(text)) offenders.push(`${rel}: ${tag}`);
  }
  assert.ok(draggables >= 2, `draggable elements found: ${draggables}`);
  assert.deepEqual(offenders, []);
});

test('the scan sees a drag-only card', () => {
  assert.equal(draggableTagsWithoutKeyboard('<div class="c" cdkDrag (click)="x()">').length, 1);
  assert.equal(
    draggableTagsWithoutKeyboard('<div cdkDrag (keydown.alt.arrowright)="m(1)">').length,
    0,
  );
  assert.equal(
    draggableTagsWithoutKeyboard('<a (dragstart)="s($event)" (keydown)="k($event)">').length,
    0,
  );
  assert.equal(draggableTagsWithoutKeyboard('<div cdkDragHandle>').length, 0);
});
