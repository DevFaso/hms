#!/usr/bin/env node
/**
 * The first version of this scan matched only `{{ x.status }}` and
 * `{{ x.status || 'y' }}`. Four new raw renders — a `??`, a ternary, a
 * `[title]` binding and a two-step optional chain — left the count unchanged,
 * so the gate reported green on exactly the defect it exists to catch. Every
 * shape that fooled it has a case here.
 *
 * The other direction matters just as much: a scan that silently stops
 * matching drops to zero findings, which reads as a huge win rather than a
 * broken parser.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { rawEnumRenders, ENUM_WORDS } from './raw-enum-scan.mjs';

const exprs = (html) => rawEnumRenders(html).map((h) => h.expr);

test('finds the plain interpolation', () => {
  assert.deepEqual(exprs('<span>{{ order.status }}</span>'), ['order.status']);
});

test('finds the shapes the first version missed', () => {
  assert.deepEqual(exprs('<p>{{ probe.status ?? "-" }}</p>'), ['probe.status']);
  assert.deepEqual(exprs('<p>{{ a.priority ? a.priority : "-" }}</p>'), [
    'a.priority',
    'a.priority',
  ]);
  assert.deepEqual(exprs('<p [title]="row.severity"></p>'), ['row.severity']);
  assert.deepEqual(exprs('<p>{{ a?.b?.status }}</p>'), ['a?.b?.status']);
});

test('matches a field whose name ENDS in an enum word', () => {
  // The miss that started all of this: a scan for a field called `.type`
  // never saw `.encounterType`.
  assert.deepEqual(exprs('<td>{{ enc.encounterType }}</td>'), ['enc.encounterType']);
  assert.deepEqual(exprs('<td>{{ l.eventType }}</td>'), ['l.eventType']);
  assert.deepEqual(exprs('<td>{{ r.reportStatus }}</td>'), ['r.reportStatus']);
});

test('a piped expression is not a finding', () => {
  assert.deepEqual(exprs("<td>{{ o.status | enumLabel: 'labOrderStatus' }}</td>"), []);
  assert.deepEqual(exprs("<td>{{ 'X.Y' | translate }}</td>"), []);
  assert.deepEqual(exprs('<td>{{ visit.date | date }}</td>'), []);
});

test('reports only the unpiped half of a binding pair', () => {
  const html = "<td>{{ a.status }}</td><td>{{ b.status | enumLabel: 'x' }}</td>";
  assert.deepEqual(exprs(html), ['a.status']);
});

test('ignores a binding whose value nobody reads', () => {
  assert.deepEqual(exprs('<div [style.background]="statusColor(row.status)"></div>'), []);
  assert.deepEqual(exprs('<div [class.is-urgent]="row.priority === \'STAT\'"></div>'), []);
});

test('reports the line the binding starts on', () => {
  const html = ['<div>', '  <span>', '    {{ x.status }}', '  </span>', '</div>'].join('\n');
  assert.deepEqual(rawEnumRenders(html), [{ expr: 'x.status', line: 3 }]);
});

test('finds every hit in one interpolation', () => {
  assert.deepEqual(exprs('<td>{{ a.status }} · {{ b.modality }}</td>'), ['a.status', 'b.modality']);
});

test('a template with nothing enum-shaped yields nothing', () => {
  assert.deepEqual(exprs('<p>{{ patient.firstName }} {{ patient.mrn }}</p>'), []);
});

test('the word list still covers the fields the tranches piped', () => {
  // A trimmed WORDS list would quietly drop findings and read as progress.
  for (const word of ['status', 'type', 'urgency', 'modality', 'gender', 'severity']) {
    assert.ok(ENUM_WORDS.includes(word), `${word} missing from ENUM_WORDS`);
  }
  assert.ok(ENUM_WORDS.length >= 27, 'ENUM_WORDS shrank — findings would drop silently');
});
