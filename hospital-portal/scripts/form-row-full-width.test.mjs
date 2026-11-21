import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * QA D2644 (r65): « Latéralité » shared a `.form-row` with a `.field.full-width`
 * (« Question clinique »). A full-width field asks for the whole row
 * (flex-basis 100%), so in a row that does not wrap it squeezed its sibling to
 * an empty 60 px select, and to 0 px at 390 with its label one letter per line.
 * A full-width field takes its own row: no `.form-row` holds it beside another field,
 * in every template whose stylesheet gives `.full-width` a 100 % flex basis (a
 * grid `.form-row`, the shared page-common one, only gives it one cell).
 */
const app = resolve(dirname(fileURLToPath(import.meta.url)), '../src/app');

/** `.full-width { flex: 1 1 100%; }`: the field asks for the whole flex row. */
const FLEX_FULL_WIDTH = /\.full-width\s*\{[^}]*flex\s*:[^;}]*100%/;

function htmlFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(dir, entry.name);
    return entry.isDirectory() ? htmlFiles(path) : entry.name.endsWith('.html') ? [path] : [];
  });
}

/** The inner markup of each `<div class="form-row" ...>`, matched by div depth. */
function formRows(source) {
  const rows = [];
  const open = /<div\b[^>]*\bclass="[^"]*\bform-row\b[^"]*"[^>]*>/g;
  let match;
  while ((match = open.exec(source))) {
    let depth = 1;
    let i = match.index + match[0].length;
    const start = i;
    const tag = /<div\b|<\/div>/g;
    tag.lastIndex = i;
    let t;
    while (depth > 0 && (t = tag.exec(source))) {
      depth += t[0] === '</div>' ? -1 : 1;
      i = t.index;
    }
    rows.push({
      line: source.slice(0, match.index).split('\n').length,
      body: source.slice(start, i),
    });
  }
  return rows;
}

test('a full-width field never shares a form row with another field (QA D2644)', () => {
  const violations = [];
  for (const path of htmlFiles(app)) {
    const scss = path.replace(/\.html$/, '.scss');
    if (!existsSync(scss) || !FLEX_FULL_WIDTH.test(readFileSync(scss, 'utf8'))) continue;
    const source = readFileSync(path, 'utf8');
    for (const row of formRows(source)) {
      const fields = row.body.match(/class="field(?:\s[^"]*)?"/g) ?? [];
      if (fields.length > 1 && fields.some((f) => /\bfull-width\b/.test(f))) {
        violations.push(`${relative(app, path).split('\\').join('/')}:${row.line}`);
      }
    }
  }
  assert.deepEqual(violations, [], 'put the full-width field in its own <div class="form-row">');
});
