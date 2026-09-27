#!/usr/bin/env node
/**
 * The inline-template scan replaced two failed regex attempts (#660): one
 * counted the pipe's TSDoc examples as call sites, the other blanked the
 * domain argument along with the comments and matched nothing while a comment
 * claimed coverage. Each of those failure shapes has a case here, and so does
 * the positive one — a scan that silently finds nothing reads as a clean tree.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

import { inlineTemplates, templatesIn } from './inline-templates.mjs';
import { rawEnumRenders } from './raw-enum-scan.mjs';

const PIPE_CALL = /enumLabel\s*:\s*'([A-Za-z_][A-Za-z0-9_]*)'/g;
const domains = (source) =>
  inlineTemplates(source).flatMap(({ text }) => [...text.matchAll(PIPE_CALL)].map((m) => m[1]));

test('finds an enumLabel call in a backtick template', () => {
  const source = [
    "import { Component } from '@angular/core';",
    '@Component({',
    "  selector: 'app-x',",
    "  template: `<span>{{ row.status | enumLabel: 'labOrderStatus' }}</span>`,",
    '})',
    'export class X {}',
  ].join('\n');
  assert.deepEqual(domains(source), ['labOrderStatus']);
});

test("an apostrophe in the markup (Aujourd'hui) does not hide the call after it", () => {
  // The naive comment blanker treated the ' in Aujourd'hui as the start of a
  // string and lost its place for the rest of the file.
  const source = [
    '@Component({',
    '  template: `',
    "    <h2>Aujourd'hui</h2>",
    "    <p>{{ a.status | enumLabel: 'encounterStatus' }}</p>",
    '    <a href="https://example.org//x">lien</a>',
    "    <p>{{ b.urgency | enumLabel: 'referralUrgency' }}</p>",
    '  `,',
    '})',
    'export class Y {}',
  ].join('\n');
  assert.deepEqual(domains(source), ['encounterStatus', 'referralUrgency']);
});

test('a TSDoc example is not a call site', () => {
  const source = [
    '/**',
    ' * Usage:',
    " *   {{ rx.status | enumLabel: 'prescriptionStatus' }}",
    ' */',
    "// {{ x.status | enumLabel: 'lineComment' }}",
    "@Component({ selector: 'app-z', templateUrl: './z.html' })",
    'export class Z {',
    "  /** {{ y.status | enumLabel: 'memberDoc' }} */",
    '  run() {}',
    '}',
  ].join('\n');
  assert.deepEqual(inlineTemplates(source), []);
});

test('a template: field on a plain object is data, not markup', () => {
  // services/nurse-task.service.ts has one.
  const source = [
    'const task = { template: "{{ t.status | enumLabel: \'x\' }}" };',
    "@Pipe({ name: 'p', template: '{{ q.status }}' } as never)",
    'export class P {}',
  ].join('\n');
  assert.deepEqual(inlineTemplates(source), []);
});

test('a single-quoted template, and a namespaced decorator, both count', () => {
  const source = [
    "@Component({ template: '<b>{{ s.priority | enumLabel: \\'taskPriority\\' }}</b>' })",
    'export class A {}',
    "@core.Component({ 'template': `{{ t.modality | enumLabel: 'imagingModality' }}` })",
    'export class B {}',
  ].join('\n');
  assert.deepEqual(domains(source), ['taskPriority', 'imagingModality']);
});

test('reports the file line the template starts on', () => {
  const source = [
    '// one',
    '// two',
    '@Component({',
    '  template: `',
    '<p>{{ z.status }}</p>`,',
    '})',
  ].join('\n');
  const [tpl] = inlineTemplates(source);
  assert.equal(tpl.line, 4);
  // …so a raw render on the template's second line is line 5 of the file.
  const [hit] = rawEnumRenders(tpl.text);
  assert.deepEqual(
    { expr: hit.expr, line: hit.line + tpl.line - 1 },
    { expr: 'z.status', line: 5 },
  );
});

test('a substitution is blanked, not read as markup', () => {
  const source = '@Component({ template: `<p>${HEADER}</p><i>{{ k.status }}</i>` })\nclass K {}';
  const [tpl] = inlineTemplates(source);
  assert.equal(tpl.text.includes('HEADER'), false);
  assert.deepEqual(
    rawEnumRenders(tpl.text).map((h) => h.expr),
    ['k.status'],
  );
});

test('templatesIn reads .html whole and .ts inline templates, never specs', () => {
  const dir = mkdtempSync(join(tmpdir(), 'p4-inline-'));
  try {
    mkdirSync(join(dir, 'feature'));
    writeFileSync(join(dir, 'feature', 'a.html'), '<p>{{ a.status }}</p>');
    writeFileSync(
      join(dir, 'feature', 'b.ts'),
      '@Component({ template: `{{ b.status }}` })\nclass B {}',
    );
    writeFileSync(
      join(dir, 'feature', 'b.spec.ts'),
      '@Component({ template: `{{ host.status }}` })\nclass Host {}',
    );
    writeFileSync(join(dir, 'feature', 'c.ts'), 'export const noTemplateHere = 1;');
    const found = templatesIn(dir)
      .map((t) => [t.file.slice(dir.length + 1).replaceAll('\\', '/'), t.text, t.lineOffset])
      .sort();
    assert.deepEqual(found, [
      ['feature/a.html', '<p>{{ a.status }}</p>', 0],
      ['feature/b.ts', '{{ b.status }}', 0],
    ]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
