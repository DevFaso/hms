// QA D1463 guard (D1431 NOT_FIXED): the patient tracker's card actions are derived from one map per
// action (TRACKER_ACTIONS over core/action-permissions.ts, which ActionPermissionsMirrorTest holds
// to the server), never from a flag naming one role. `frontDeskOnly` left the hospital admin 54
// cards of buttons that could only answer 403.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'src',
  'app',
  'patient-tracker',
);
const html = readFileSync(join(dir, 'patient-tracker.component.html'), 'utf8');
const ts = readFileSync(join(dir, 'patient-tracker.component.ts'), 'utf8');

/** The `@if (...)` conditions open around `index`, innermost last. */
export function openConditions(source, index) {
  const open = [];
  const re = /@if\s*\(|@else|}/g;
  let depth = [];
  for (const m of source.slice(0, index).matchAll(re)) {
    if (m[0] === '}') {
      depth.pop();
    } else if (m[0] === '@else') {
      depth.push('@else');
    } else {
      let i = m.index + m[0].length;
      let parens = 1;
      while (parens > 0 && i < source.length) {
        if (source[i] === '(') parens++;
        if (source[i] === ')') parens--;
        i++;
      }
      depth.push(source.slice(m.index + m[0].length, i - 1));
    }
  }
  open.push(...depth);
  return open;
}

const GATES = /canOffer\(|canStartItem\(|canEndExamItem\(|canCloseUnseen/;

test('every action button of a card sits under a gate from the action map', () => {
  const offenders = [];
  const cardStart = html.indexOf('class="card-footer"');
  assert.ok(cardStart > 0, 'the card footer is found');
  for (const m of html.matchAll(/<button\b/g)) {
    if (m.index < cardStart) continue;
    const conditions = openConditions(html, m.index);
    if (!conditions.some((c) => GATES.test(c))) {
      offenders.push(html.slice(m.index, m.index + 120).replace(/\s+/g, ' '));
    }
  }
  assert.deepEqual(offenders, []);
});

test('no action is gated by a flag naming a role', () => {
  const offenders = [];
  for (const m of html.matchAll(/<button\b/g)) {
    const conditions = openConditions(html, m.index);
    if (conditions.some((c) => /frontDeskOnly|isReceptionist/.test(c))) {
      offenders.push(html.slice(m.index, m.index + 120).replace(/\s+/g, ' '));
    }
  }
  assert.deepEqual(offenders, []);
});

test('the map names every card action, each through core/action-permissions', () => {
  for (const action of [
    'assessTriage',
    'completeTriage',
    'closeUnseen',
    'startVisit',
    'completeExamination',
    'readyForDischarge',
    'checkout',
  ]) {
    assert.match(ts, new RegExp(`${action}: \\[`), action);
  }
  assert.match(ts, /satisfies Record<string, readonly ActionKey\[\]>/);
  // What opens on GET /encounters/{id} needs that read too: the first button's 403 was the read.
  assert.match(ts, /assessTriage: \['encounterRead'/);
  assert.match(ts, /checkout: \['encounterRead'/);
});

test('the scan sees the shape the defect had', () => {
  const bad = "@if (!frontDeskOnly && col.key === 'x') { <button>y</button> }";
  const at = bad.indexOf('<button');
  assert.ok(openConditions(bad, at).some((c) => /frontDeskOnly/.test(c)));
  assert.ok(!openConditions(bad, at).some((c) => GATES.test(c)));
});
