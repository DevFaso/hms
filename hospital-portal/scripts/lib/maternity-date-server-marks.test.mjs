// QA D1446 guard (round 34): the server refused a time 10 hours ahead under
// `fieldErrors.observationTime`, and the partograph, post-partum and newborn forms
// showed only a toast: their `#pe-time`, `#obs-time` and `#nb-time` do not read as
// that key, so nothing could mark them. The delivery form had the map.
//
// Rule: every date or time input of the maternity screens can be found from the
// server's key, by a `data-server-field` on the control or by its id being a value of
// a field map in the component beside the template.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const maternityDir = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'src',
  'app',
  'maternity',
);
const DATE_INPUT = /<input\b[^>]*?\btype="(?:date|datetime-local|time)"[^>]*>/g;
const ID = /\bid="([^"]+)"/;

export function unmarkableDateInputs(template, component) {
  const unmarkable = [];
  for (const [input] of template.matchAll(DATE_INPUT)) {
    if (input.includes('data-server-field=')) continue;
    const id = ID.exec(input)?.[1];
    if (!id || !component.includes(`'${id}'`)) unmarkable.push(id ?? input);
  }
  return unmarkable;
}

test('every date or time input of the maternity screens can be marked from the server key', () => {
  const problems = [];
  let inputs = 0;
  for (const file of readdirSync(maternityDir).filter((f) => f.endsWith('.html'))) {
    const template = readFileSync(join(maternityDir, file), 'utf8');
    const component = readFileSync(join(maternityDir, file.replace(/\.html$/, '.ts')), 'utf8');
    inputs += [...template.matchAll(DATE_INPUT)].length;
    for (const id of unmarkableDateInputs(template, component)) problems.push(`${file}: #${id}`);
  }
  assert.ok(inputs >= 15, `the scan found the inputs (${inputs})`);
  assert.deepEqual(problems, []);
});

test('the scan catches the round-34 shape', () => {
  const template = '<input id="pe-time" type="datetime-local" [(ngModel)]="f.observationTime" />';
  assert.deepEqual(unmarkableDateInputs(template, "const OTHER = { a: 'pe-sys' };"), ['pe-time']);
  assert.deepEqual(
    unmarkableDateInputs(template, "const IDS = { observationTime: 'pe-time' };"),
    [],
  );
  assert.deepEqual(
    unmarkableDateInputs('<input id="x" data-server-field="y" type="date" />', ''),
    [],
  );
});
