// Round 29 (D1256 / D1272 / D1245 class): the enumerating half of the
// behavioural form guard — see lib/form-behaviour-scan.mjs. These pin what
// the scan counts as a submit, how a spec "drives" one, and that a guarded
// form must map a 400's fieldErrors.
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  anchorOf,
  drivenSelectors,
  formBehaviourFindings,
  isSubmitControl,
  mapsFieldErrors,
  specDrives,
} from './form-behaviour-scan.mjs';
import { tags } from './html-tags.mjs';

const first = (html) => tags(html).find((t) => t.type === 'open');
const noSpecs = () => [];

test('a guarded button and a type=submit are submits; a plain button is not', () => {
  assert.equal(isSubmitControl(first('<button appSubmitGuard (click)="a()">x</button>')), true);
  assert.equal(isSubmitControl(first('<button [appSubmitGuard]="!x">x</button>')), true);
  assert.equal(isSubmitControl(first('<button type="submit">x</button>')), true);
  assert.equal(isSubmitControl(first('<input type="submit" />')), true);
  assert.equal(isSubmitControl(first('<button (click)="cancel()">x</button>')), false);
});

test('the anchor is the static data-testid, then the id, then the handler', () => {
  assert.deepEqual(anchorOf(first('<button data-testid="t" id="i" type="submit">x</button>')), {
    anchor: 'data-testid=t',
    name: 't',
    nameable: true,
  });
  assert.equal(anchorOf(first('<button id="i" type="submit">x</button>')).anchor, 'id=i');
  const dyn = anchorOf(
    first('<button [attr.data-testid]="x" (click)="save(r)" appSubmitGuard>x</button>'),
  );
  assert.deepEqual(dyn, { anchor: '(click)=save', name: '', nameable: false });
  assert.equal(anchorOf(first('<button type="submit">x</button>')).anchor, 'type=submit');
});

test('only the arguments of expectEmptySubmitRefusal( count as driving a submit', () => {
  const spec = `
    root.querySelector('[data-testid="held"]');
    expectEmptySubmitRefusal(fixture, { button: '[data-testid="verify"]' });
    expectEmptySubmitRefusal(fixture, { button: '#defTestCode', sent: spy });
  `;
  assert.equal(drivenSelectors(spec).length, 2);
  assert.equal(specDrives([spec], 'verify'), true);
  assert.equal(specDrives([spec], 'defTestCode'), true);
  assert.equal(specDrives([spec], 'held'), false, 'a querySelector is not a drive');
  assert.equal(specDrives([spec], 'defTest'), false, 'a prefix of an id is not that id');
});

test('an undriven submit is a finding; a driven one is not', () => {
  const html = '<form><button type="submit" data-testid="save" appSubmitGuard>x</button></form>';
  const ts = 'refusedFieldReasons(err, IDS)';
  assert.deepEqual(
    formBehaviourFindings(html, ts, 'src/app/x/x.html', noSpecs).map((f) => f.rule),
    ['empty-submit-untested'],
  );
  const driven = () => [`expectEmptySubmitRefusal(f, { button: '[data-testid="save"]' })`];
  assert.deepEqual(formBehaviourFindings(html, ts, 'src/app/x/x.html', driven), []);
});

test('a guarded form whose component never maps fieldErrors is a finding', () => {
  const html = '<button data-testid="s" appSubmitGuard>x</button>';
  const driven = () => [`expectEmptySubmitRefusal(f, { button: '[data-testid="s"]' })`];
  const found = formBehaviourFindings(html, 'this.toast.error(msg)', 'src/app/x/x.html', driven);
  assert.deepEqual(
    found.map((f) => [f.rule, f.anchor]),
    [['server-field-errors-unmapped', 'component']],
  );
  assert.equal(mapsFieldErrors('const r = refusedFieldReasons(err, IDS);'), true);
  assert.equal(mapsFieldErrors('this.serverFieldErrors = fieldErrorsOf(err);'), true);
  // An unguarded type=submit form (no required-field refusal of its own) is not held to the mapping.
  const plain = '<form><button type="submit" data-testid="s">x</button></form>';
  assert.deepEqual(formBehaviourFindings(plain, '', 'src/app/x/x.html', driven), []);
});
