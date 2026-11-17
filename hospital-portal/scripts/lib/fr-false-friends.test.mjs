// QA D1054 guard (round 17): an English verb translated by its French false
// friend. "Acknowledge" (a critical BPA alert) was "Reconnaître" ("recognise");
// French clinical usage is "Prendre acte" / "Accuser réception". The scan reads
// every French string the portal and the backend show and fails on a listed
// false friend. Add a pair when a round finds another one.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const portalFr = join(here, '..', '..', 'src', 'assets', 'i18n', 'fr.json');
const backendI18n = join(
  here,
  '..',
  '..',
  '..',
  'hospital-core',
  'src',
  'main',
  'resources',
  'i18n',
);

/** French false friend -> what the source word means here. */
export const FALSE_FRIENDS = [
  { pattern: /\bReconna[iî]tre\b/i, english: 'acknowledge', use: 'Prendre acte' },
];

function strings(value, path = '') {
  if (typeof value === 'string') return [[path, value]];
  if (value && typeof value === 'object') {
    return Object.entries(value).flatMap(([k, v]) => strings(v, path ? `${path}.${k}` : k));
  }
  return [];
}

export function falseFriendOffenders(entries) {
  return entries.flatMap(([key, text]) =>
    FALSE_FRIENDS.filter((f) => f.pattern.test(text)).map(
      (f) => `${key}: "${text}" (for "${f.english}" use "${f.use}")`,
    ),
  );
}

test('QA D1054: no French string uses a listed false friend', () => {
  const entries = strings(JSON.parse(readFileSync(portalFr, 'utf8')));
  for (const file of readdirSync(backendI18n).filter((f) => f.endsWith('_fr.properties'))) {
    for (const line of readFileSync(join(backendI18n, file), 'utf8').split(/\r?\n/)) {
      const eq = line.indexOf('=');
      if (eq > 0 && !line.startsWith('#'))
        entries.push([`${file}:${line.slice(0, eq)}`, line.slice(eq + 1)]);
    }
  }
  assert.ok(entries.length > 1000);
  assert.deepEqual(falseFriendOffenders(entries), []);
});

test('QA D1054 guard: the scan sees the shape the defect had', () => {
  assert.equal(falseFriendOffenders([['BPA.ACTION_ACKNOWLEDGE', 'Reconnaître']]).length, 1);
  assert.equal(falseFriendOffenders([['BPA.ACTION_ACKNOWLEDGE', 'Prendre acte']]).length, 0);
});
