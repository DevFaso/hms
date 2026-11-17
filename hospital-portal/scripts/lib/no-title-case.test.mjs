// QA D1010: translated labels are written in sentence case in every language
// ("Spécialiste de la facturation"). A stylesheet that title-cases them
// rewrote them as "Spécialiste De La Facturation" and "Patient(E)", and the
// titlecase pipe does the same in a template. Neither may come back.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const srcDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');

function files(dir, exts) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return files(p, exts);
    return exts.some((e) => name.endsWith(e)) && !name.endsWith('.spec.ts') ? [p] : [];
  });
}

test('D1010: no stylesheet capitalizes text', () => {
  const offenders = files(srcDir, ['.scss', '.css'])
    .filter((f) => /text-transform:\s*capitalize/.test(readFileSync(f, 'utf8')))
    .map((f) => relative(srcDir, f));
  assert.deepEqual(offenders, []);
});

test('D1010: no template title-cases a label', () => {
  const offenders = files(srcDir, ['.html', '.ts'])
    .filter((f) => /\|\s*titlecase\b|TitleCasePipe/.test(readFileSync(f, 'utf8')))
    .map((f) => relative(srcDir, f));
  assert.deepEqual(offenders, []);
});
