import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const primaryFallback =
  /(?:this\.)?roleContext\.activeHospitalId\s*\?\?\s*(?:this\.)?auth\.getHospitalId\s*\(/g;
function files(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(dir, entry.name);
    return entry.isDirectory() ? files(path) : /\.(?:ts|java)$/.test(entry.name) ? [path] : [];
  });
}

test('clinical hospital requests never fall from request scope back to the account primary hospital (SA-SCOPE-1)', () => {
  const violations = [];
  for (const path of [
    ...files(resolve(root, 'hospital-portal/src')),
    ...files(resolve(root, 'hospital-core/src')),
  ]) {
    if (/\.(?:spec|test)\.ts$/.test(path)) continue;
    const source = readFileSync(path, 'utf8');
    const matches = [...source.matchAll(primaryFallback)];
    if (!matches.length) continue;
    const name = relative(root, path).replaceAll('\\', '/');
    // Not an instance: imaging explicitly returns the super-admin's hospitalFilter
    // (or null for the global view) BEFORE its non-super-admin primary fallback.
    // Keep the exception constrained to that branch rather than exempting any file.
    if (
      name === 'hospital-portal/src/app/imaging/imaging.ts' &&
      matches.length === 1 &&
      /private resultsHospitalId\(\)[^{]*\{\s*if \(this\.canPickHospital\) return this\.hospitalFilter\(\) \|\| null;/.test(
        source,
      )
    )
      continue;
    violations.push(name);
  }
  assert.deepEqual(
    violations,
    [],
    'Use effectiveHospitalIdForRequest(); a null global scope must stay null',
  );
});

test('scope guard recognizes the original primary-hospital fallback', () => {
  assert.equal(
    [...'this.roleContext.activeHospitalId ?? this.auth.getHospitalId()'.matchAll(primaryFallback)]
      .length,
    1,
  );
});
