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

test('a hit inside a wrapped attribute reports its own line, not the name', () => {
  // Prettier wraps a long [title] ternary; the build error must point at the
  // line holding the field, not at `[title]=` three lines up.
  const bound = [
    '<span',
    '  [title]="',
    '    flag',
    '      ? row.severity',
    "      : ''",
    '  "',
    '></span>',
  ];
  assert.deepEqual(rawEnumRenders(bound.join('\n')), [{ expr: 'row.severity', line: 4 }]);
  const interpolated = ['<img', '  alt="prefix', '  {{ p.severity }}"', '/>'];
  assert.deepEqual(rawEnumRenders(interpolated.join('\n')), [{ expr: 'p.severity', line: 3 }]);
});

test('a wrapped interpolation reports the line the field is on', () => {
  const html = ['<p>', '  {{', '    a.label ??', '      a.status', '  }}', '</p>'].join('\n');
  assert.deepEqual(rawEnumRenders(html), [{ expr: 'a.status', line: 4 }]);
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
  assert.ok(ENUM_WORDS.includes('role'), 'role missing from ENUM_WORDS');
  assert.ok(ENUM_WORDS.length >= 28, 'ENUM_WORDS shrank — findings would drop silently');
});

test('an interpolation inside a CSS class is not a render', () => {
  // The second version's blind spot, and a quarter of the first baseline: the
  // class name is machinery, and the label beside it was usually already piped.
  assert.deepEqual(exprs('<span class="status-badge {{ getStatusClass(o.status) }}"></span>'), []);
  assert.deepEqual(exprs('<span class="pill" [ngClass]="statusClass(e.status)"></span>'), []);
  assert.deepEqual(exprs('<span class="s-{{ a.status | lowercase }}"></span>'), []);
});

test('the real shape: class interpolation beside a translated label', () => {
  const html = [
    '<span class="status-badge {{ statusClass(r.status) }}">',
    "  {{ 'TRANSFUSION.REQUEST_STATUS_' + r.status | translate }}",
    '</span>',
  ].join('\n');
  assert.deepEqual(exprs(html), []);
});

test('the text beside a class binding is still a render', () => {
  const html = '<span class="pill" [ngClass]="statusClass(e.status)">{{ e.status }}</span>';
  assert.deepEqual(rawEnumRenders(html), [{ expr: 'e.status', line: 1 }]);
});

test('a data attribute is machinery; a title is read', () => {
  assert.deepEqual(exprs('<td [attr.data-status]="shift.status"></td>'), []);
  assert.deepEqual(exprs('<td [title]="shift.status"></td>'), ['shift.status']);
});

test('every text-bearing attribute on the list is read', () => {
  // One case per entry, so an entry that stops matching fails here rather
  // than reading as config a future reader has to re-verify by hand.
  for (const attr of [
    'title',
    'alt',
    'placeholder',
    'aria-label',
    'aria-description',
    'aria-valuetext',
    'label',
  ]) {
    assert.deepEqual(exprs(`<x ${attr}="{{ q.priority }}"></x>`), ['q.priority'], attr);
    assert.deepEqual(exprs(`<x [attr.${attr}]="q.priority"></x>`), ['q.priority'], attr);
  }
});

test('an Angular Material attribute is not on the list', () => {
  // No template in src/app uses a Material component; see TEXT_ATTRS.
  assert.deepEqual(exprs('<td matTooltip="{{ x.priority }}"></td>'), []);
});

test('blanking an attribute keeps the line numbers honest', () => {
  // Attribute values are blanked in place, newlines kept, so a render below a
  // multi-line tag still reports its own line.
  const html = ['<span', '  class="a {{ f(x.status) }}"', '>', '  {{ y.status }}', '</span>'].join(
    '\n',
  );
  assert.deepEqual(rawEnumRenders(html), [{ expr: 'y.status', line: 4 }]);
});

test('a resolved half does not excuse a raw one in the same attribute', () => {
  // The whole attribute value used to be tested for a resolving pipe at once,
  // so a badge tooltip combining a status and a timestamp went uncounted.
  assert.deepEqual(exprs('<img alt="{{ p.severity }} {{ p.when | date }}">'), ['p.severity']);
  assert.deepEqual(exprs(`<td title="{{ a.status }} / {{ b.status | enumLabel: 'x' }}">`), [
    'a.status',
  ]);
});

test('spaces around the equals sign do not shift the blanking window', () => {
  // Hand-computed offsets put the window on the attribute NAME here, blanking
  // `class = "` and leaving the tail of the value to be scanned as text.
  assert.deepEqual(exprs('<td class = "{{ f(y.status) }}">'), []);
  assert.deepEqual(exprs('<td title = "{{ y.status }}">'), ['y.status']);
});

test('a single-quoted attribute is an attribute', () => {
  assert.deepEqual(exprs("<span class='badge {{ f(o.status) }}'>ok</span>"), []);
  assert.deepEqual(exprs("<span title='{{ o.status }}'>ok</span>"), ['o.status']);
});

test('an interpolated value= is painted; a [value] binding is not', () => {
  assert.deepEqual(exprs('<input value="{{ v.status }}">'), ['v.status']);
  assert.deepEqual(exprs('<input\n  type="text"\n  value="{{ v.status }}"\n/>'), ['v.status']);
  // `<option [value]="b.status">` carries the form value; its label is separate.
  assert.deepEqual(exprs('<option [value]="b.status">x</option>'), []);
});

test('an option or button value= is the form value, not the label', () => {
  // The label beside it is what a person reads, and here it is piped: counting
  // the value would pin a site whose on-screen text is already translated.
  const html = `<option value="{{ o.status }}">{{ o.status | enumLabel: 'x' }}</option>`;
  assert.deepEqual(exprs(html), []);
  assert.deepEqual(exprs('<button value="{{ o.status }}">Go</button>'), []);
  // …while a raw label beside it is still a finding.
  assert.deepEqual(exprs('<option value="{{ o.status }}">{{ o.status }}</option>'), ['o.status']);
});

test('a < inside an earlier attribute is not mistaken for a tag', () => {
  const html = '<input *ngIf="a < b" value="{{ v.status }}">';
  assert.deepEqual(exprs(html), ['v.status']);
});

test('commented-out markup is not on screen', () => {
  // Counting it mints baseline pins for dead code, and uncommenting the block
  // then reports those pins stale.
  assert.deepEqual(exprs('<!-- {{ z.status }} -->'), []);
  assert.deepEqual(exprs('<!-- note -->\n<td>{{ z.status }}</td>'), ['z.status']);
});

test('a comment keeps the lines below it honest', () => {
  const html = ['<!--', '  {{ ignored.status }}', '-->', '{{ real.status }}'].join('\n');
  assert.deepEqual(rawEnumRenders(html), [{ expr: 'real.status', line: 4 }]);
});

test('a property read OFF an enum-named field is not that field', () => {
  // `card.source.label` renders the CDS card's source LABEL, free text the
  // service sends; matching `card.source` inside it pinned a site that was
  // never an enum render.
  assert.deepEqual(exprs('<span>{{ card.source.label }}</span>'), []);
  assert.deepEqual(exprs('<span>{{ card.source?.label }}</span>'), []);
  assert.deepEqual(exprs('<span>{{ card.source!.label }}</span>'), []);
  // …while the field itself, and a method called on it, still are.
  assert.deepEqual(exprs('<span>{{ card.source }}</span>'), ['card.source']);
  assert.deepEqual(exprs('<span>{{ card?.source ?? "-" }}</span>'), ['card?.source']);
});
