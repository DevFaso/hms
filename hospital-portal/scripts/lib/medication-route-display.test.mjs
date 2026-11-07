// QA D950 guard: a medication route is never shown as its stored code.
//
// The MAR showed "ORAL", "INTRAVENOUS", "SUBCUTANEOUS" in a French screen, and
// the eMAR "right route" check passed only on that exact English word. Every
// template that interpolates a route goes through the medicationRoute pipe,
// and the pipe's synonym list is the server's (MedicationRoutes.java), which
// the five-rights check uses — so what the nurse reads, types and is checked
// against is one vocabulary.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const appDir = join(here, '..', '..', 'src', 'app');
const javaRoutes = join(
  here,
  '..',
  '..',
  '..',
  'hospital-core',
  'src',
  'main',
  'java',
  'com',
  'example',
  'hms',
  'service',
  'emar',
  'MedicationRoutes.java',
);
const pipeFile = join(appDir, 'shared', 'pipes', 'medication-route.pipe.ts');

/** Interpolations of a `.route` field that skip the pipe (fluid I/O routes use routeLabel). */
export function rawRouteInterpolations(html, file = 'template') {
  const offenders = [];
  for (const m of html.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
    const expr = m[1];
    if (
      /\.route\b/.test(expr) &&
      !/\|\s*medicationRoute\b/.test(expr) &&
      !/routeLabel\(/.test(expr)
    ) {
      offenders.push(`${file}:${html.slice(0, m.index).split('\n').length} ${expr.trim()}`);
    }
  }
  return offenders;
}

test('QA D950: every route shown in a template goes through | medicationRoute', () => {
  const offenders = walk(appDir, ['.html']).flatMap((f) =>
    rawRouteInterpolations(readFileSync(f, 'utf8'), relative(appDir, f).split('\\').join('/')),
  );
  assert.deepEqual(offenders, []);
});

test('QA D950: the portal and the five-rights check share one route vocabulary', () => {
  const java = Object.fromEntries(
    [...readFileSync(javaRoutes, 'utf8').matchAll(/Map\.entry\("([A-Z]+)", "([A-Z]+)"\)/g)].map(
      (m) => [m[1], m[2]],
    ),
  );
  const ts = Object.fromEntries(
    [...readFileSync(pipeFile, 'utf8').matchAll(/^\s+([A-Z]+): '([A-Z]+)',$/gm)].map((m) => [
      m[1],
      m[2],
    ]),
  );
  assert.ok(Object.keys(java).length > 40);
  assert.deepEqual(ts, java);
});

test('QA D950 guard: the scan sees the shape the defect had', () => {
  assert.equal(rawRouteInterpolations('<td>{{ m.route }}</td>').length, 1);
  assert.equal(rawRouteInterpolations('<td>{{ m.route | medicationRoute }}</td>').length, 0);
});
