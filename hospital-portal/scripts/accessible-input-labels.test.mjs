import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

test('inputs have at most one bound accessible label across templates and inline components', () => {
  const root = fileURLToPath(new URL('../src/app/', import.meta.url));
  const failures = [];
  function walk(dir) {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const file = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(file);
        continue;
      }
      if ((!file.endsWith('.html') && !file.endsWith('.ts')) || file.endsWith('.spec.ts')) continue;
      const text = readFileSync(file, 'utf8');
      for (const [input] of text.matchAll(/<input\b[^>]*>/g)) {
        if ([...input.matchAll(/\[attr\.aria-label\]\s*=/g)].length > 1) failures.push(file);
      }
    }
  }
  walk(root);
  assert.deepEqual(failures, [], 'one accessible-label binding per input');
});
