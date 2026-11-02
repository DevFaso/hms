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
import {
  gluedBadges,
  gluedBefore,
  gluedHints,
  gluedLifecycleNotes,
} from './badge-spacing-scan.mjs';

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
  assert.deepEqual(
    found,
    [],
    "separate it with {{ ' ' }} (a &ngsp; right after a block's } is dropped)",
  );
});

test('QA D1205 guard: the scan sees the round-22 row and the shapes that are spaced', () => {
  const pending = `<td>
      @if (r.supersedesResultId) {
        <span class="status-badge status-correction">{{ 'C' | translate }}</span>
      }
      &ngsp;<span class="status-badge status-pending">P</span>
    </td>`;
  assert.equal(
    gluedBefore(pending, pending.indexOf('<span class="status-badge status-pending"')),
    true,
  );
  const fixed = pending.replace(
    '&ngsp;<span class="status-badge status-pending"',
    '{{ \' \' }}<span class="status-badge status-pending"',
  );
  assert.equal(
    gluedBefore(fixed, fixed.indexOf('<span class="status-badge status-pending"')),
    false,
  );
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

// QA D1271 (round 26): the MRN hint ran into the birth-date hint in the
// waitlist and recall patient suggestions ("CHW7213(3 nov. 1993)"). The scan
// now reads every hint / meta / sub-line element too (gluedHints). The 74
// glued hints the first run found across 40 templates were spaced with
// {{ ' ' }}. The one left is in the eMAR, owned by the MAR branch of round 26
// (fix/r26-mar-schedule): pinned with its defect id, and the pin fails once
// the site is spaced, so it cannot outlive the fix.
// Round 27 (fix/r27-portal-markers-focus) spaced the eMAR queue meta: none is left.
const HINT_ALLOWED = new Map();

test('QA D1271: no hint, meta or sub-line runs into the text before it, in any template', () => {
  const found = [];
  const seen = new Set();
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const g of gluedHints(text)) {
      const site = `${rel}:${g.line + (lineOffset ?? 0)}`;
      if (HINT_ALLOWED.has(site)) seen.add(site);
      else found.push(`${site} ${g.tag}`);
    }
  }
  assert.deepEqual(found, [], "separate it with {{ ' ' }}");
  const stale = [...HINT_ALLOWED.keys()].filter((s) => !seen.has(s));
  assert.deepEqual(stale, [], 'the site was fixed: delete its line from HINT_ALLOWED');
});

test('QA D1271 guard: the scan sees the round-26 suggestion row', () => {
  const row = `<li>{{ p | personName }}
      @if (p | patientSubline; as sub) {
        <span class="mrn-hint">· {{ sub }}</span>
      }
      @if (p.dateOfBirth) {
        <span class="dob-hint">({{ p.dateOfBirth | date }})</span>
      }
    </li>`;
  assert.deepEqual(
    gluedHints(row).map((g) => g.tag),
    ['<span class="dob-hint"'],
  );
  const fixed = row.replace('<span class="dob-hint"', '{{ \' \' }}<span class="dob-hint"');
  assert.deepEqual(gluedHints(fixed), []);
});

// QA D1288 (D1271 NOT_FIXED in round 27): the inactive-guarantor and
// inactive-allergy notes glued the time to "Motif" — plain <span>s, which the
// class-name rule above never read. Every inline element that shows a
// lifecycle reason or a close time (deactivated, revoked, suspended,
// cancelled, archived …) is now read too. The first run found 18 sites in 12
// templates (the two reported notes, the tenant lifecycle rows, the
// correction banners, the label/value pairs of reasons); all were spaced.
// Allowlist: none.
test('QA D1288: no lifecycle note (a reason, a close time) runs into the text before it', () => {
  const found = [];
  let notes = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    notes += (text.match(/Reason\b|(?:deactivated|revoked|suspended|cancelled)At\b/g) ?? []).length;
    for (const g of gluedLifecycleNotes(text))
      found.push(`${rel}:${g.line + (lineOffset ?? 0)} ${g.tag}`);
  }
  assert.ok(notes >= 100, `the lifecycle notes must be read, saw ${notes}`);
  assert.deepEqual(found, [], "separate it with {{ ' ' }} (and a ' · ' when it joins a time)");
});

test('QA D1288 guard: the scan sees the round-27 guarantor note', () => {
  const note = `<div class="muted">
      @if (g.deactivatedAt) {
        <span>{{ g.deactivatedAt | serverTime | date: 'short' }}</span>
      }
      @if (g.deactivationReason) {
        <span>{{ 'COVERAGE.DEACTIVATION_REASON' | translate }} {{ g.deactivationReason }}</span>
      }
    </div>`;
  assert.equal(gluedLifecycleNotes(note).length, 1);
  const fixed = note.replace("<span>{{ 'COVERAGE", "{{ ' ' }}<span>{{ 'COVERAGE");
  assert.deepEqual(gluedLifecycleNotes(fixed), []);
  // A label and its reason, side by side.
  const pair = `<span class="field-label">{{ 'X' | translate }}</span>
      <span class="field-value">{{ o.cancellationReason }}</span>`;
  assert.equal(gluedLifecycleNotes(pair).length, 1);
  // A reason that is not an inline element's text, or that starts a block, is not this class.
  assert.deepEqual(gluedLifecycleNotes('<p>{{ o.cancellationReason }}</p>'), []);
});
