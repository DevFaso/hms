#!/usr/bin/env node
/**
 * QA D1130 guard (round 20): a button that opens a dialog with NO target.
 *
 * "Poser une question" on Mon éducation santé called `openAsk(null)`, and the
 * dialog was shown while `askTarget() !== null`: the general ask set the
 * target to null, so it never opened. The class is an open state derived from
 * a nullable target that the template legitimately sets to null.
 *
 * Rule: wherever a template calls `openX(null)`, the component's `openX` must open the dialog by a state of its
 * own — set a flag to true (`.set(true)` / `= true`) — not leave "open" to a
 * target that is null in this very call. Allowlist: none.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { relative } from 'node:path';

import { walk } from './walk.mjs';
import { balanced } from './js-scan.mjs';
import { SRC_DIR, PORTAL_DIR } from './template-gate.mjs';

const NULL_OPEN = /\(click\)="\s*(open[A-Z]\w*)\(\s*null\s*\)\s*"/g;

/** The body of method `name` in `ts`, or null. */
export function methodBody(ts, name) {
  const decl = new RegExp(String.raw`(?:^|[\s;}])${name}\s*\([^)]*\)\s*(?::[^{;]+)?\{`, 'm');
  const m = decl.exec(ts);
  if (!m) return null;
  return balanced(ts, m.index + m[0].length - 1, '{', '}');
}

/** Does this open method open by a state of its own (not only by setting a target)? */
export function opensByOwnState(body) {
  return /\.set\(\s*true\s*\)|=\s*true\b|\.open\(|\.showModal\(\)|router\.navigate|\.dialog\./.test(body);
}

test('the D1130 shape is caught: openAsk(null) with open = target !== null', () => {
  const body = methodBody(
    `openAsk(item: X | null): void {\n this.askTarget.set(item);\n this.askText = '';\n }`,
    'openAsk',
  );
  assert.equal(opensByOwnState(body), false);
  assert.equal(opensByOwnState(`{ this.askTarget.set(item); this.askOpen.set(true); }`), true);
});

test('every template: an open-with-no-target button opens a dialog by a state of its own', () => {
  const offenders = [];
  let seen = 0;
  for (const html of walk(SRC_DIR, ['.html'])) {
    const text = readFileSync(html, 'utf8');
    const ts = html.replace(/\.html$/, '.ts');
    if (!existsSync(ts)) continue;
    const source = readFileSync(ts, 'utf8');
    for (const m of text.matchAll(NULL_OPEN)) {
      const body = methodBody(source, m[1]);
      if (body === null) continue;
      seen++;
      if (!opensByOwnState(body)) {
        offenders.push(`${relative(PORTAL_DIR, html).replaceAll('\\', '/')}: ${m[0]}`);
      }
    }
  }
  assert.ok(seen >= 2, `only ${seen} open-with-no-target buttons found: the scan lost its shape`);
  assert.deepEqual(offenders, []);
});
