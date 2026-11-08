import { readdirSync, readFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const appRoot = fileURLToPath(new URL('../src/app/', import.meta.url));

function sources(root) {
  return readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const path = join(root, entry.name);
    return entry.isDirectory()
      ? sources(path)
      : /\.(html|ts)$/.test(path) && !path.endsWith('.spec.ts')
        ? [path]
        : [];
  });
}

test('every result evolution caller supplies an identity to reset result-specific display state', () => {
  const missing = [];
  for (const path of sources(appRoot)) {
    for (const tag of readFileSync(path, 'utf8').matchAll(
      /<app-result-evolution\b(?:[^>"']|"[^"]*"|'[^']*')*>/g,
    )) {
      if (!tag[0].includes('[resultId]')) missing.push(path);
    }
  }
  assert.deepEqual(missing, []);
});

test('the shared evolution view retains observation deltas and sorted rows', () => {
  const component = readFileSync(
    resolve(appRoot, 'lab/shared/result-evolution.component.ts'),
    'utf8',
  );
  assert.match(component, /LAB_RESULTS\.DELTA/);
  assert.match(component, /of observations\(\)/);
  assert.match(component, /@if\s*\(!hasCorrections\(\)\)\s*\{\s*<polyline/);
});
