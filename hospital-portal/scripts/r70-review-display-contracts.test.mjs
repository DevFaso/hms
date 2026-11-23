import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { walk } from './lib/walk.mjs';
import { fileURLToPath } from 'node:url';

const app = fileURLToPath(new URL('../src/app/', import.meta.url));
const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');

test('every portal override-description lookup includes the global control scope', () => {
  let lookups = 0;
  for (const file of walk(app, ['.ts']).filter((file) => !file.endsWith('.spec.ts'))) {
    const source = readFileSync(file, 'utf8');
    for (const lookup of source.matchAll(
      /this\.overrides\(\)\.find\(([\s\S]*?)\)\?\.description/g,
    )) {
      lookups++;
      assert.match(lookup[1], /override\.organizationId\s*==\s*null/, file);
    }
  }
  assert.ok(lookups > 0);
  const flags = read('../src/app/feature-flags/feature-flags.ts');
  const search = flags.match(/filteredFlags = computed\(([\s\S]*?)\n {2}\}\);/)?.[1];
  assert.ok(search);
  assert.match(search, /this\.searchableDescription\(f\)/);
  assert.doesNotMatch(search, /this\.flagDescription\(f\)/);
  assert.match(
    read('../src/app/feature-flags/feature-flags.html'),
    /@if \(flagTitle\(flag\) !== flag\.key\)/,
  );
});

test('all revision kinds share a neutral authoring title and signed-source heading', () => {
  const template = read('../src/app/imaging/imaging.html');
  assert.match(template, /writingAddendum\(\)\s*\?\s*'IMAGING\.REVISE_SIGNED_REPORT'/);
  assert.match(template, /'IMAGING\.REVISION_ORIGINAL'\s*\|\s*translate/);
  for (const locale of ['en', 'fr', 'es']) {
    const labels = JSON.parse(read(`../src/assets/i18n/${locale}.json`)).IMAGING;
    for (const key of ['REVISE_SIGNED_REPORT', 'REVISION_ORIGINAL']) {
      assert.ok(labels[key]);
      assert.doesNotMatch(labels[key], /addend|complet|complét|amplía/i);
    }
  }
});

test('the specimen scroll instruction is limited to widths where the dialog table overflows', () => {
  assert.match(read('../src/app/lab/lab.html'), /class="field-hint specimen-scroll-hint"/);
  const styles = read('../src/app/lab/lab.scss');
  assert.match(styles, /\.specimen-scroll-hint\s*\{\s*display:\s*none;/);
  assert.match(
    styles,
    /@media \(max-width: 900px\)\s*\{\s*\.specimen-scroll-hint\s*\{\s*display:\s*block;/,
  );
});
