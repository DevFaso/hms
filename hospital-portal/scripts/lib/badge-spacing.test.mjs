// QA D1205 / D1167 / D1137 guard (round 22): two status words glued together.
//
// Round 21's spacing check (lab-correction-shown.test.mjs) covered only the
// shared correction badge and the hand-written `status-correction` marker. On
// /lab-results the PENDING badge after it still read "RectificationEn attente
// de publication": its `&ngsp;` came right after the `}` of the correction's
// `@if`, where Angular drops it. This scan reads every status pill of every
// template (an element whose class names a `status-badge`, `badge`, `chip` or
// `pill`) and fails when it runs into the inline element or interpolation
// before it (scripts/lib/badge-spacing-scan.mjs). Allowlist: none — the 142
// glued pills the first run found across 72 templates were all spaced with
// `{{ ' ' }}`.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { gluedBadges, gluedBefore } from './badge-spacing-scan.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

test('QA D1205: no status pill runs into the word before it, in any template', () => {
  const found = [];
  let templates = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    templates++;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const g of gluedBadges(text)) found.push(`${rel}:${g.line + (lineOffset ?? 0)} ${g.tag}`);
  }
  assert.ok(templates > 200, `every template must be read, saw ${templates}`);
  assert.deepEqual(found, [], "separate it with {{ ' ' }} (a &ngsp; right after a block's } is dropped)");
});

test('QA D1205 guard: the scan sees the round-22 row and the shapes that are spaced', () => {
  const pending = `<td>
      @if (r.supersedesResultId) {
        <span class="status-badge status-correction">{{ 'C' | translate }}</span>
      }
      &ngsp;<span class="status-badge status-pending">P</span>
    </td>`;
  assert.equal(gluedBefore(pending, pending.indexOf('<span class="status-badge status-pending"')), true);
  const fixed = pending.replace('&ngsp;<span class="status-badge status-pending"', "{{ ' ' }}<span class=\"status-badge status-pending\"");
  assert.equal(gluedBefore(fixed, fixed.indexOf('<span class="status-badge status-pending"')), false);
  // `&ngsp;` that does not follow a block's `}` is kept by Angular.
  const inBlock = `<span class="status-badge a">A</span>
      @if (x) {
        &ngsp;<span class="status-badge b">B</span>
      }`;
  assert.equal(gluedBefore(inBlock, inBlock.indexOf('<span class="status-badge b"')), false);
  // A pill written on its own line after a span is glued (the line break is dropped).
  const twoLines = '<strong>Awa</strong>\n  <span class="badge">VIP</span>';
  assert.equal(gluedBefore(twoLines, twoLines.indexOf('<span class="badge"')), true);
  // The first thing in its cell, or after a block element, runs into nothing.
  assert.equal(gluedBefore('<td>\n <span class="badge">x</span>', 6), false);
  const afterDiv = '<div>A</div>\n<span class="badge">x</span>';
  assert.equal(gluedBefore(afterDiv, afterDiv.indexOf('<span')), false);
  // Whitespace after an interpolation stays inside its text node; none glues.
  const spaced = '{{ name }} <span class="chip">x</span>';
  assert.equal(gluedBefore(spaced, spaced.indexOf('<span')), false);
  const glued = '{{ name }}<span class="chip">x</span>';
  assert.equal(gluedBefore(glued, glued.indexOf('<span')), true);
  // `} @else {`: the other branch never renders beside this one.
  const branches = `@if (a) { <span class="x">A</span> } @else { <span class="badge">B</span> }`;
  assert.equal(gluedBefore(branches, branches.indexOf('<span class="badge"')), false);
});
