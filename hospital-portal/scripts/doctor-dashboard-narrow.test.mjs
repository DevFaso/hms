import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import assert from 'node:assert/strict';

/**
 * QA D2262 (remainder, r65 → r77): on the doctor's dashboard at 390 px the worklist filters (476 px on one
 * line) and the « Planning du jour » header (360 px) overflowed their 340 px card. The class: a dashboard row
 * of controls laid out with `display: flex` and no wrap. Each row below must wrap, and what it holds must be
 * allowed to shrink; a new one-line row of controls on the dashboard joins this list.
 */
const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');

/** The body of the first top-level rule for `selector` in `scss`. */
function rule(scss, selector) {
  const text = scss.replaceAll('\r\n', '\n');
  // A top-level rule starts its line; a nested one is indented.
  const at = text.startsWith(`${selector} {`) ? 0 : text.indexOf(`\n${selector} {`);
  assert.notEqual(at, -1, `${selector} has no top-level rule`);
  return text.slice(at, text.indexOf('\n}', at + 1));
}

const ROWS = {
  '../src/app/dashboard/doctor-worklist/doctor-worklist.scss': ['.wl-filters'],
  '../src/app/dashboard/dashboard.scss': ['.card-head', '.card-head-right'],
};

for (const [path, selectors] of Object.entries(ROWS)) {
  for (const selector of selectors) {
    test(`${selector} wraps on a narrow screen`, () => {
      const body = rule(read(path), selector);
      assert.match(body, /display: flex;/);
      assert.match(body, /flex-wrap: wrap;/, `${selector} in ${path} is a one-line row again`);
    });
  }
}

test('the worklist filter controls may shrink to the card', () => {
  const scss = read('../src/app/dashboard/doctor-worklist/doctor-worklist.scss');
  for (const selector of ['.wl-filter-select', '.wl-filter-date']) {
    assert.match(rule(scss, selector), /max-width: 100%;/);
  }
});
