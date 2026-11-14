// Exercise the actual shared tick generator in a bounded VM: a regression
// must fail a test rather than hanging the runner (or the result drawer).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';

const source = readFileSync(
  new URL('../../src/app/lab/shared/result-evolution.ts', import.meta.url),
  'utf8',
);
const start = source.indexOf('export function niceAxis(');
const end = source.indexOf('export function previousObservation(');
assert.ok(start >= 0 && end > start, 'shared axis implementation must be found');
const code = ts.transpile(source.slice(start, end).replace('export function', 'function'));

test('shared lab axis terminates with finite distinct ticks across numeric magnitudes', () => {
  const inputs = [
    [1e307, 2e307],
    [-Number.MAX_VALUE, Number.MAX_VALUE],
  ];
  for (let exponent = -323; exponent <= 308; exponent++) {
    const value = 10 ** exponent;
    inputs.push([value, value * 2], [-value, value], [value, value]);
  }
  const context = vm.createContext({ inputs });
  const axes = new vm.Script(`${code}\ninputs.map(([lo, hi]) => niceAxis(lo, hi));`).runInContext(
    context,
    { timeout: 1000 },
  );
  assert.ok(axes[0], 'large finite bounds should produce an axis');
  for (const axis of axes) {
    if (!axis) continue; // Unsupported arithmetic must fail closed.
    assert.ok(Number.isFinite(axis.min) && Number.isFinite(axis.max) && axis.max > axis.min);
    assert.ok(Number.isFinite(axis.step) && axis.step > 0);
    assert.ok(axis.ticks.length >= 2 && axis.ticks.length <= 102);
    assert.ok(axis.decimals >= 0 && axis.decimals <= 20);
    axis.ticks.forEach((tick, index) => {
      assert.ok(Number.isFinite(tick));
      if (index) assert.ok(tick > axis.ticks[index - 1]);
    });
  }
});
