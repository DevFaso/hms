// QA D945: a dashboard tile counts what its label says. The receptionist's
// "Enregistré" (DASHBOARD.CHECKED_IN) tile showed the COMPLETED count. Every
// tile labelled CHECKED_IN must show checkedInToday(), and a tile showing
// completedToday() must not be labelled CHECKED_IN.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const html = readFileSync(
  join(
    dirname(fileURLToPath(import.meta.url)),
    '..',
    '..',
    'src',
    'app',
    'dashboard',
    'dashboard.html',
  ),
  'utf8',
);

test('D945: every CHECKED_IN tile counts checked-in visits', () => {
  const bodies = [...html.matchAll(/<div class="stat-body">([\s\S]*?)<\/div>/g)].map((m) => m[1]);
  const checkedIn = bodies.filter((b) => b.includes("'DASHBOARD.CHECKED_IN'"));
  assert.ok(checkedIn.length >= 1);
  for (const body of checkedIn) {
    assert.match(body, /checkedInToday\(\)/);
    assert.doesNotMatch(body, /completedToday\(\)/);
  }
});
