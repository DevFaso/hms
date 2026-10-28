#!/usr/bin/env node
/**
 * The English-literal scan for component code. The failing cases are the
 * shapes a toast or a status signal is written in; the passing cases are the
 * translated ones and the things that look like prose but are keys or data.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { tsLiteralFindings, isProseLiteral } from './ts-literal-scan.mjs';

const anchors = (source) => tsLiteralFindings(source).map((f) => `${f.rule}:${f.anchor}`);

test('an English sentence handed to a toast fails, in any quote style', () => {
  const source = `this.toast.error('Could not save the appointment');
    this.toastService.success("Appointment saved");
    this.toast.warning(\`Slot \${slot} is taken\`);`;
  assert.deepEqual(anchors(source), [
    'toast:Could not save the appointment',
    'toast:Appointment saved',
    'toast:Slot ${slot} is taken',
  ]);
});

test('a status signal or field set to an English sentence fails', () => {
  const source = `this.error.set('Something went wrong');
    this.errorMessage = 'Both fields are required.';
    this.hint.set("Pick a date first");`;
  assert.deepEqual(anchors(source), [
    'signal:error Something went wrong',
    'signal:errorMessage Both fields are required.',
    'signal:hint Pick a date first',
  ]);
});

test('translated text, keys, variables and single tokens pass', () => {
  const source = `this.toast.success(this.translate.instant('APPOINTMENTS.SAVED'));
    this.toast.error('APPOINTMENTS.SAVE_FAILED');
    this.toast.error(err?.error?.message ?? this.translate.instant('COMMON.ERROR'));
    this.error.set('');
    this.error.set(msg);
    this.message = 'ok';`;
  assert.deepEqual(anchors(source), []);
});

test('isProseLiteral: two words of letters, not a SECTION.KEY', () => {
  assert.ok(isProseLiteral('Passwords do not match.'));
  assert.ok(isProseLiteral('Le mot de passe'));
  assert.equal(isProseLiteral('PORTAL.MEDICATIONS.NOT_REFILLABLE_CONTROLLED'), false);
  assert.equal(isProseLiteral('saved'), false);
  assert.equal(isProseLiteral('${a} ${b}'), false);
});
