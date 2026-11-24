import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/*
 * The portal's list of POSTs that only read (src/app/core/read-only-posts.ts) is the backend's
 * list: every POST handler marked @ReadsOnly, no more, no less.
 *
 * The portal reads that list to tell a read from a write (the timeout banner, the chart-change
 * notice, a refusal on a landing page). A search moved from GET to POST and missing here would
 * be shown as "the action may have been saved"; a write listed here would be shown as a read.
 */

const portal = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const javaRoot = resolve(portal, '../hospital-core/src/main/java');
const { READ_ONLY_POST_ROUTES, isReadOnlyPost, isReadRequest } =
  await import('../src/app/core/read-only-posts.ts');

function javaFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return javaFiles(path);
    return entry.name.endsWith('.java') ? [path] : [];
  });
}

/** The first string literal of an annotation's arguments, or '' for a bare annotation. */
function annotationPath(text, name, where) {
  const at = new RegExp(String.raw`@${name}\b(\s*\()?`).exec(text);
  if (!at) return null;
  if (!at[1]) return '';
  const args = text.slice(at.index + at[0].length);
  const close = args.search(/\)\s*(?:@|$|\n)/);
  const inside = close === -1 ? args : args.slice(0, close);
  const literal = /"([^"]*)"/.exec(inside);
  if (literal) return literal[1];
  assert.ok(
    !/\w/.test(inside.replace(/\b(produces|consumes)\s*=.*/s, '')),
    `${where}: @${name} path is not a literal`,
  );
  return '';
}

const MEMBER = /^\s*(public|private|protected)\b.*\(/;
const READS_ONLY = /@(?:com\.example\.hms\.security\.)?ReadsOnly\s*\(/;

function backendReadOnlyPosts() {
  const routes = new Set();
  for (const file of javaFiles(javaRoot)) {
    const source = readFileSync(file, 'utf8');
    if (!READS_ONLY.test(source)) continue;
    const where = relative(javaRoot, file);
    const classAt = source.search(/^public\s+(?:final\s+)?class\b/m);
    const header = source.slice(0, classAt);
    const prefix = annotationPath(header, 'RequestMapping', where) ?? '';
    const lines = source.split('\n');
    lines.forEach((line, index) => {
      if (!READS_ONLY.test(line) || /^\s*(\*|\/\/|import)/.test(line)) return;
      // The method's annotations: up to the blank line, `;` or `}` above, and the signature below.
      let start = index;
      while (start > 0 && !/^\s*$|[;}]\s*$/.test(lines[start - 1])) start--;
      let end = index;
      while (end < lines.length - 1 && !MEMBER.test(lines[end])) end++;
      const block = lines.slice(start, end + 1).join('\n');
      const path = annotationPath(block, 'PostMapping', `${where}:${index + 1}`);
      assert.notEqual(
        path,
        null,
        `${where}:${index + 1}: @ReadsOnly on a handler that is not a POST`,
      );
      routes.add(`${prefix}${path}`.replace(/\/$/, ''));
    });
  }
  return routes;
}

test('the portal lists exactly the POSTs the backend marks @ReadsOnly', () => {
  const backend = backendReadOnlyPosts();
  assert.ok(
    backend.size >= 20,
    `only ${backend.size} @ReadsOnly POST routes found: the scan broke`,
  );
  const portalRoutes = new Set(READ_ONLY_POST_ROUTES);
  const missing = [...backend].filter((route) => !portalRoutes.has(route)).sort();
  const extra = [...portalRoutes].filter((route) => !backend.has(route)).sort();
  assert.deepEqual(missing, [], 'read-only POSTs missing from src/app/core/read-only-posts.ts');
  assert.deepEqual(
    extra,
    [],
    'routes in src/app/core/read-only-posts.ts the backend does not mark @ReadsOnly',
  );
});

test('the predicate recognises the routes as the portal sends them, and nothing else', () => {
  const id = '3f2c1a9e-8b7d-4c6e-9f10-a1b2c3d4e5f6';
  for (const url of [
    '/patients/search?hospitalId=h1&size=20',
    '/api/patients/lookup',
    'patients/registration-match',
    '/api/reception/patients/duplicate-candidates',
    'https://app.example.org/api/super-admin/audit-search?page=0',
    '/api/super-admin/audit-search/csv?maxRows=10000',
    `/patients/${id}/doctor-timeline`,
    '/users/search',
    '/encounters/by-doctor',
  ]) {
    assert.ok(isReadOnlyPost('POST', url), url);
    assert.ok(isReadRequest('POST', url), url);
  }
  for (const url of [
    '/patients',
    `/patients/${id}/allergies`,
    '/patients/search/extra',
    '/reception/check-in',
    '/encounters',
  ]) {
    assert.ok(!isReadOnlyPost('POST', url), url);
  }
  assert.ok(!isReadOnlyPost('PUT', '/patients/search'));
  assert.ok(isReadRequest('GET', '/patients'));
});
