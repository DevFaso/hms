import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';

function missingSourceCheck(text) {
  return [...text.matchAll(/{{[\s\S]*?}}/g)].filter(
    ([binding]) =>
      binding.includes('SUPER_ADMIN.AUDIT_NONE') &&
      !/recentAudit\(\)\.length\s*>\s*0/.test(binding),
  ).length;
}

function sources(root) {
  return readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const path = join(root, entry.name);
    return entry.isDirectory() ? sources(path) : /\.(html|ts)$/.test(entry.name) ? [path] : [];
  });
}

test('every recent-audit source-empty binding distinguishes a nonempty source', () => {
  const root = fileURLToPath(new URL('../../src/app/', import.meta.url));
  const violations = sources(root)
    .filter((path) => missingSourceCheck(readFileSync(path, 'utf8')))
    .map((path) => relative(root, path));
  assert.deepEqual(
    violations,
    [],
    'Audit filters must not report an empty source when received records are hidden',
  );
});

test('the audit empty-state guard detects the unguarded filter branch', () => {
  assert.equal(
    missingSourceCheck(
      "{{ (auditFailuresOnly() ? 'SUPER_ADMIN.AUDIT_EMPTY' : 'SUPER_ADMIN.AUDIT_NONE') | translate }}",
    ),
    1,
  );
  assert.equal(
    missingSourceCheck(
      "{{ (auditFailuresOnly() || recentAudit().length > 0 ? 'SUPER_ADMIN.AUDIT_EMPTY' : 'SUPER_ADMIN.AUDIT_NONE') | translate }}",
    ),
    0,
  );
});
