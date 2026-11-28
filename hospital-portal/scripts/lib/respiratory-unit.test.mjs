import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';

// Respirations are breaths per minute; bpm denotes heart beats.
test('respiratory rate labels use per-minute units in triage and vital signs', () => {
  for (const [file, label] of [
    ['src/app/nurse-station/triage-form/triage-form.component.html', 'TRIAGE.RESPIRATORY_RATE'],
    ['src/app/nurse-station/nurse-station.html', 'NURSE.RESP_RATE'],
  ]) {
    const html = readFileSync(file, 'utf8');
    const line = html.split('\n').find((value) => value.includes(label));
    assert.ok(line, `${file}: missing respiratory label`);
    assert.match(line, /\(\/min\)/);
    assert.doesNotMatch(line, /\(bpm\)/i);
  }
});
