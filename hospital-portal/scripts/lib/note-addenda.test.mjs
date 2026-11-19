// QA D2260 (r63, High) guard: an addendum to a signed note was saved and shown
// nowhere. A signed note is locked, so its addendum is the only way to correct
// it; a screen that shows the note without its addenda shows a wrong record.
//
// Rule: every template that renders a note (a note's signature or documented
// time, `note.signedAt`, `n.signed`, `entry.documentedAt`, …, or the after-visit
// summary's signed-note sections `clinicalAssessment` / `clinicalPlan`) renders
// `<app-note-addenda>`, which shows the addenda under it, dated and signed.
// Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const NOTE_READER =
  /\b(?:note|n|entry|view\.entry)\.(?:signedAt|signed|documentedAt)\b|\bsummary\(\)\.clinical(?:Assessment|Plan)\b/;
const ADDENDA = /<app-note-addenda\b/;

/** The component's template: its templateUrl file, or its inline template. */
function templateOf(file, source) {
  const url = /templateUrl:\s*['"]([^'"]+)['"]/.exec(source)?.[1];
  if (url) {
    const path = join(dirname(file), url);
    return existsSync(path) ? readFileSync(path, 'utf8') : '';
  }
  const inline = /template:\s*`([\s\S]*?)`/.exec(source)?.[1];
  return inline ?? '';
}

export function noteReaders() {
  const readers = [];
  const missing = [];
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const source = readFileSync(file, 'utf8');
    if (!/@Component\(/.test(source)) continue;
    // The renderer itself.
    if (/[\\/]shared[\\/]note-addenda[\\/]note-addenda\.component\.ts$/.test(file)) continue;
    const template = templateOf(file, source);
    if (!NOTE_READER.test(template)) continue;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    readers.push(rel);
    if (!ADDENDA.test(template)) missing.push(rel);
  }
  return { readers, missing };
}

test('QA D2260: every screen that shows a note shows its addenda', () => {
  const { readers, missing } = noteReaders();
  // The visit, its nursing-notes panel, the chart review and the visit summary at least.
  assert.ok(
    readers.length >= 4,
    `found only ${readers.length} note readers: ${readers.join(', ')}`,
  );
  assert.deepEqual(
    missing,
    [],
    'render <app-note-addenda [addenda]="…"> under the note (QA D2260)',
  );
});
