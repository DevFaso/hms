#!/usr/bin/env node
/**
 * The shared literal/comment walker every portal gate steps through sources with.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { balanced, endOfLiteralOrComment } from './js-scan.mjs';

test('an apostrophe inside a comment does not open a string', () => {
  const source = `(a /* it's */, 'b') + tail('x')`;
  assert.equal(balanced(source, 0), `(a /* it's */, 'b')`);
  const lineComment = `(a, // the patient's amount\n  'b') + tail('x')`;
  assert.equal(balanced(lineComment, 0), `(a, // the patient's amount\n  'b')`);
});

test('a closer inside a string or a template literal closes nothing', () => {
  assert.equal(
    balanced(`{ const u = '}'; const t = \`)\`; } rest`, 0, '{', '}'),
    `{ const u = '}'; const t = \`)\`; }`,
  );
  assert.equal(balanced(`(x(')'), y) z`, 0), `(x(')'), y)`);
});

test('endOfLiteralOrComment: the last character of a literal or comment, -1 elsewhere', () => {
  assert.equal(endOfLiteralOrComment(`'a\\'b' c`, 0), 5);
  assert.equal(endOfLiteralOrComment(`/* x */ y`, 0), 6);
  assert.equal(endOfLiteralOrComment(`// x\ny`, 0), 4);
  assert.equal(endOfLiteralOrComment(`a / b`, 2), -1);
});
