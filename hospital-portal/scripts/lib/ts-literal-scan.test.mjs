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
    this.toast.warning(\`Slot \${slot} is taken\`);
    this.toast.push('error', 'Could not reach the server');
    this.toast.error(err?.error?.message ?? 'Could not save the visit');
    this.error.set(e.message || 'Something went wrong');`;
  // The literal may sit anywhere in the argument: a `??`/`||` fallback is the usual spelling.
  assert.deepEqual(anchors(source), [
    'toast:Could not save the appointment',
    'toast:Appointment saved',
    'toast:Slot ${slot} is taken',
    'toast:Could not reach the server',
    'toast:Could not save the visit',
    'signal:error Something went wrong',
  ]);
});

test('a status signal or field set to an English sentence fails, however it is declared', () => {
  const source = `this.error.set('Something went wrong');
    this.errorMessage = 'Both fields are required.';
    this.hint.set("Pick a date first");
    readonly notice = signal('Nothing selected yet');
    message: string = 'Please wait a moment';
    private banner = signal<string | null>('Service is down');`;
  assert.deepEqual(anchors(source), [
    'signal:error Something went wrong',
    'signal:errorMessage Both fields are required.',
    'signal:hint Pick a date first',
    'signal:notice Nothing selected yet',
    'signal:message Please wait a moment',
    'signal:banner Service is down',
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

test('a comment is not code, and the fallback of translate.instant never reaches the screen', () => {
  const source = `this.error =
      // \`error\` is the reason phrase. The backend's message is translated.
      err?.error?.message ?? this.translate.instant('LOGIN.FAILED');
    // this.toast.error('Could not save the appointment');
    this.toast.success(
      this.translate.instant('PLATFORM.TOAST.STATUS_CHANGED', {
        // interpolating the raw token produced "modifié en DECOMMISSIONED".
        status: label,
      }),
    );
    this.toast.success(this.translate.instant('checkout.success') || 'Patient checked out successfully');
    this.toast.error(this.translate.instant('x.y', { n }) ?? 'Failed to acknowledge');
    readonly info = computed(() => { const n = this.count(); return n ? 'Some items selected' : ''; });
    const labels = rows.map(message => 'Shown nowhere at all');
    if (warning === 'Not yet keyed here') { return; }`;
  // The computed body is part of the assignment: its English still counts.
  assert.deepEqual(anchors(source), ['signal:info Some items selected']);
});

test('the line of a finding below a block comment is the line it is on', () => {
  const source = `/* one
 two */
this.toast.error('Could not save the appointment');`;
  assert.deepEqual(
    tsLiteralFindings(source).map((f) => f.line),
    [3],
  );
});

test('isProseLiteral: two words of letters, not a SECTION.KEY', () => {
  assert.ok(isProseLiteral('Passwords do not match.'));
  assert.ok(isProseLiteral('Le mot de passe'));
  assert.equal(isProseLiteral('PORTAL.MEDICATIONS.NOT_REFILLABLE_CONTROLLED'), false);
  assert.equal(isProseLiteral('saved'), false);
  assert.equal(isProseLiteral('${a} ${b}'), false);
});
