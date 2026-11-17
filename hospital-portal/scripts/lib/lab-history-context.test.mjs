import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import ts from 'typescript';

const app = fileURLToPath(new URL('../../src/app/', import.meta.url));
const read = (path) => readFileSync(join(app, path), 'utf8');
function load(source, dependencies = {}) {
  const exports = {};
  const context = vm.createContext({ exports, require: (id) => dependencies[id] });
  new vm.Script(
    ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }),
  ).runInContext(context, { timeout: 1000 });
  return exports;
}

test('a correction anywhere in lab history cannot become a previous measurement', () => {
  const dates = load(read('shared/date-utils.ts'));
  const { previousObservation } = load(read('lab/shared/result-evolution.ts'), {
    '../../shared/date-utils': dates,
    './lab-unit-conversion': {},
  });
  const current = { resultDate: '2026-11-13T09:00:00Z', resultValue: '1.05', resultUnit: 'g/L' };
  const earlier = { ...current, resultDate: '2026-11-12T09:00:00Z', resultValue: '1.32' };
  assert.equal(previousObservation([earlier], current).point.resultValue, '1.32');
  for (const history of [
    [{ ...earlier, corrected: true }],
    [earlier, { ...current, corrected: true }],
    [{ ...earlier, resultDate: '2026-11-01T09:00:00Z', corrected: true }, earlier],
  ])
    assert.equal(previousObservation(history, current), null);
  assert.equal(previousObservation([earlier], { ...current, corrected: true }), null);
});

test('all lab day-month history labels explicitly retain the year', () => {
  const files = readdirSync(join(app, 'lab'), { recursive: true }).filter(
    (p) => /\.(ts|html)$/.test(p) && !p.endsWith('.spec.ts'),
  );
  let count = 0;
  for (const file of files) {
    const source = read(join('lab', file));
    for (const call of source.matchAll(/\|\s*dayMonth([^}\n]*)/g)) {
      assert.match(call[1], /:\s*undefined\s*:\s*true/, file);
      count++;
    }
    for (const call of source.matchAll(/this\.dayMonth\.transform\(([^\n]*)\)/g)) {
      assert.match(call[1], /,\s*undefined,\s*true\s*$/, file);
      count++;
    }
  }
  assert.ok(count >= 3, 'chart endpoints and previous measurement must be covered');
});
