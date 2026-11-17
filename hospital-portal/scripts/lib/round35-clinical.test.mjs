// QA round 35 guards with no gate of their own.
//
// D1479: a wait written as raw minutes (`{{ item.waitMinutes }}m` read "7151m"). A template shows
// a count of minutes through the `elapsed` pipe. Allowlist: none.
//
// D1480: a button escape-keeps-typed-text.ts builds at run time is outside every component's
// encapsulated styles, so each class it sets must have a rule in the global stylesheet.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const RAW_MINUTES = /\{\{[^}|]*[mM]inutes[^}|]*\}\}\s*(?:m|min)\b/g;

function templates(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return templates(path);
    return entry.name.endsWith('.html') ? [path] : [];
  });
}

export function rawMinutes(html) {
  return [...html.matchAll(RAW_MINUTES)].map((m) => m[0]);
}

test('no template writes a count of minutes by hand', () => {
  const found = templates(join(src, 'app')).flatMap((path) =>
    rawMinutes(readFileSync(path, 'utf8')).map((hit) => `${path}: ${hit}`),
  );
  assert.deepEqual(found, []);
});

test('the scan catches the round-35 shape, and leaves the pipe alone', () => {
  assert.equal(rawMinutes('{{ item.waitMinutes }}m').length, 1);
  assert.equal(rawMinutes('{{ item.elapsedMinutes }} min').length, 1);
  assert.equal(rawMinutes('{{ item.waitMinutes | elapsed }}').length, 0);
});

test('every button the Escape question builds at run time has a global style', () => {
  const script = readFileSync(
    join(src, 'app', 'shared', 'a11y', 'escape-keeps-typed-text.ts'),
    'utf8',
  );
  const styles = readFileSync(join(src, 'styles.scss'), 'utf8');
  const classes = [...script.matchAll(/(?:keep|discard)\.className = '([\w-]+)'/g)].map(
    (m) => m[1],
  );
  assert.deepEqual(classes.sort(), ['btn-danger', 'btn-secondary']);
  for (const name of classes) {
    assert.ok(
      styles.includes(`.dialog-discard .${name}`),
      `.dialog-discard .${name} in styles.scss`,
    );
  }
});
