import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bareControlFindings, controlsIn, NATIVE_EXCEPTIONS } from './form-control-design.mjs';

const fixture = (text) => [{ rel: 'src/app/admin/example.ts', text }];

test('finds bare text controls in conditional inline templates, even with a cosmetic class', () => {
  const findings = bareControlFindings(
    fixture('@if (ready()) { <input class="legacy"><select></select><textarea></textarea> }'),
  );
  assert.equal(findings.length, 3);
});

test('accepts established fields, native primitives and Material form components', () => {
  assert.deepEqual(
    bareControlFindings(
      fixture(
        '<div class="form-group"><input></div><div class="field"><textarea></textarea></div><select appFormControl></select><input class="keneya-form-control"><mat-form-field><input matInput></mat-form-field>',
      ),
    ),
    [],
  );
  for (const [type, reason] of Object.entries(NATIVE_EXCEPTIONS)) {
    assert.ok(reason.length > 30);
    assert.deepEqual(bareControlFindings(fixture(`<input type="${type}">`)), []);
  }
});

test('requires a reason for each exact exception and refuses stale or obsolete exceptions', () => {
  const text = '<input id="special-search" class="special">';
  const control = controlsIn(text, 'src/app/admin/example.ts')[0];
  const exceptions = {
    [control.key]: {
      reason: 'Borderless search input within the separately bordered, padded search surface.',
    },
  };
  assert.deepEqual(bareControlFindings(fixture(text), exceptions), []);
  assert.equal(bareControlFindings(fixture(text), { [control.key]: { reason: '' } }).length, 1);
  assert.equal(
    bareControlFindings(fixture(text + '<input id="new-control">'), exceptions).length,
    1,
  );
  assert.equal(bareControlFindings(fixture('<input id="different">'), exceptions).length, 2);
  const styled = '<input class="keneya-form-control">';
  const styledKey = controlsIn(styled, 'src/app/admin/example.ts')[0].key;
  assert.equal(
    bareControlFindings(fixture(styled), { [styledKey]: exceptions[control.key] }).length,
    1,
  );
});

test('a card alone does not style a bare control, and formatting is not a new exception', () => {
  assert.equal(bareControlFindings(fixture('<form class="form-card"><input></form>')).length, 1);
  const a = controlsIn('<input id="code" name="code">', 'page.ts')[0].key;
  const b = controlsIn('<input\n id="code"\n name="code">', 'page.ts')[0].key;
  assert.equal(a, b);
  const nested = controlsIn('<div class="custom"><input id="code" name="code"></div>', 'page.ts')[0]
    .key;
  assert.notEqual(a, nested, 'moving an exception out of its styled context needs review');
});

test('an exception for one control never exempts an identical sibling', () => {
  const text = '<input id="special-search" class="special">';
  const first = controlsIn(text, 'src/app/admin/example.ts')[0];
  const exceptions = {
    [first.key]: {
      reason: 'Borderless search control inside its independently styled search surface.',
    },
  };
  const controls = controlsIn(text + text, 'src/app/admin/example.ts');
  assert.notEqual(controls[0].key, controls[1].key);
  assert.equal(bareControlFindings(fixture(text + text), exceptions).length, 1);
});

test('visits empty/deferred branches and does not treat virtual containers as styled wrappers', () => {
  const templates = fixture(
    '@for (row of rows; track row) { <div>{{row}}</div> } @empty { <input> } @defer { <span>Ready</span> } @placeholder { <textarea></textarea> }',
  );
  assert.equal(bareControlFindings(templates).length, 2);
  assert.equal(
    bareControlFindings(fixture('<ng-container class="form-group"><input></ng-container>')).length,
    1,
  );
  const inline = [{ ...fixture('<input>')[0], lineOffset: 20 }];
  assert.match(bareControlFindings(inline)[0], /:21: bare input/);
});
