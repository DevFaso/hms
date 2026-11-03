// QA D1389 guard (round 32): an undo or terminal-action reason shown bare.
//
// A reversed adjustment or payment showed its undo reason as a lone line under
// the "Annulé le…" badge, where it read as the ORIGINAL reason of the
// adjustment. A reason that belongs to an action taken on the record (a
// reversal, a cancellation, a void, a revocation, a suspension, a rejection,
// a correction, an amendment …) is shown with its label: inside the sentence
// (`{{ 'COMMON.REASON_VALUE' | translate: { reason: a.reversalReason } }}`), or
// after a label the same element carries just before it (a key ending in
// REASON / REASON_LABEL, a `<dt>` / `.field-label`, or COMMON.COLON).
//
// Rule, over every template (each `.html` and each inline `template:`): an
// interpolation whose whole value is such a `…Reason` property fails unless a
// translate key containing REASON, a COLON key, or a label element sits in the
// 400 characters before it. Known bare sites are pinned with their file and
// why; a fixed one fails until its pin is deleted.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/** The reason of an action taken ON the record, as opposed to the record's own reason. */
const ACTION_REASON =
  /\{\{\s*([\w.!?()]+\.(?:reversal|cancel|cancellation|void|revocation|deactivation|suspension|archive|purge|rejection|decline|correction|amendment)Reason)\s*\}\}/g;
/** A label the reader sees just before the value. */
const LABEL_BEFORE = /REASON|COLON|<dt\b|field-label|MOTIF/;
const LOOK_BACK = 400;

/** The bare action reasons of a template: `{ line, value }`. */
export function unlabelledReasons(html) {
  const out = [];
  for (const m of html.matchAll(ACTION_REASON)) {
    // A spacing interpolation ({{ ' ' }}) is not a value between the label and this one.
    const before = html
      .slice(Math.max(0, m.index - LOOK_BACK), m.index)
      .replace(/\{\{\s*' '\s*\}\}/g, ' ');
    // The label belongs to this value only if no other value sits between them.
    const lastClose = before.lastIndexOf('}}');
    const lastLabel = Math.max(
      ...['REASON', 'COLON', '<dt', 'field-label', 'MOTIF'].map((t) => before.lastIndexOf(t)),
    );
    if (LABEL_BEFORE.test(before) && lastLabel > lastClose - 60) continue;
    out.push({ line: html.slice(0, m.index).split('\n').length, value: m[1] });
  }
  return out;
}

/** file → why it is still bare (none for billing after D1389). */
const PINNED = new Map([]);

test('QA D1389: an action reason is shown with its label in every template', () => {
  const found = [];
  let templates = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    templates++;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const r of unlabelledReasons(text)) {
      if (PINNED.has(rel)) continue;
      found.push(`${rel}:${r.line + (lineOffset ?? 0)} {{ ${r.value} }}`);
    }
  }
  assert.ok(templates > 200, `every template must be read, saw ${templates}`);
  assert.deepEqual(
    found,
    [],
    "label the reason: {{ 'COMMON.REASON_VALUE' | translate: { reason: x.reversalReason } }}",
  );
});

test('QA D1389 guard: the scan sees a bare reversal reason, not a labelled one', () => {
  const bare =
    '<span class="badge">{{ \'BILLING.ADJUSTMENT_REVERSED_BADGE\' | translate }}</span>\n' +
    '<span class="field-hint">{{ a.reversalReason }}</span>';
  assert.deepEqual(unlabelledReasons(bare), [{ line: 2, value: 'a.reversalReason' }]);
  const labelled =
    "<span>{{ 'COMMON.REASON_VALUE' | translate: { reason: a.reversalReason } }}</span>";
  assert.deepEqual(unlabelledReasons(labelled), []);
  const afterLabel =
    '<span class="field-label">{{ \'BILLING.CANCEL_REASON\' | translate }}</span>\n<p>{{ inv.cancelReason }}</p>';
  assert.deepEqual(unlabelledReasons(afterLabel), []);
  const ownReason = '<span class="field-hint">{{ a.reason }}</span>';
  assert.deepEqual(unlabelledReasons(ownReason), []);
});
