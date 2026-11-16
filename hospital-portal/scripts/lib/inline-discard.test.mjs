import { test } from 'node:test';
import assert from 'node:assert/strict';
import { inlineDiscardFindings as scan } from './dialog-scan.mjs';

test('D1971: a dirty inline form needs a visible discard question bound to that form', () => {
  assert.equal(
    scan('<div appInlineSubForm (dismissed)="back()"><textarea></textarea></div>').length,
    1,
  );
  assert.equal(
    scan(
      '<div appInlineSubForm #own="inlineSubForm"><textarea></textarea><app-inline-discard-ask [form]="other" /></div>',
    ).length,
    1,
  );
  assert.deepEqual(
    scan(
      '<div appInlineSubForm #own="inlineSubForm"><textarea></textarea><app-inline-discard-ask [form]="own" /></div>',
    ),
    [],
  );
});

test('D1971: non-text and read-only inline controls cannot have typed text to discard', () => {
  for (const field of [
    '<input type="radio">',
    '<input type="checkbox">',
    '<input type="file">',
    '<textarea readonly></textarea>',
    '<select></select>',
  ]) {
    assert.deepEqual(scan(`<div appInlineSubForm>${field}</div>`), []);
  }
  assert.equal(scan('<div appInlineSubForm><input type="text"></div>').length, 1);
});
