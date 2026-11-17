import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import ts from 'typescript';

function violations(source) {
  const ast = ts.createSourceFile('source.ts', source, ts.ScriptTarget.Latest, true);
  const found = [];
  function visit(node) {
    if (ts.isCallExpression(node)) {
      const call = node.getText(ast);
      const target = node.expression.getText(ast);
      if (
        /(?:console|logger)\.(?:log|debug|info|warn|error)/.test(target) &&
        /nationalUniqueId|000123456789/i.test(call)
      )
        found.push('logged');
      if (
        /\.(?:get|set|navigate|navigateByUrl)$/.test(target) &&
        /nationalUniqueId(?!Masked|Mode)/.test(call)
      )
        found.push('url');
    }
    ts.forEachChild(node, visit);
  }
  visit(ast);
  return found;
}

test('IU privacy guard rejects logging and query strings, and accepts a JSON POST body', () => {
  assert.deepEqual(violations('console.log({ nationalUniqueId });'), ['logged']);
  assert.deepEqual(violations('params.set("nationalUniqueId", value);'), ['url']);
  assert.deepEqual(violations('http.get(`/patients?nationalUniqueId=${value}`);'), ['url']);
  assert.deepEqual(
    violations('http.post("/patients/national-unique-id/search", { nationalUniqueId });'),
    [],
  );
});

test('all production IU callers keep the value out of logs and URLs', () => {
  function files(directory) {
    return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
      const path = join(directory, entry.name);
      return entry.isDirectory() ? files(path) : [path];
    });
  }
  const root = fileURLToPath(new URL('../../src/app', import.meta.url));
  for (const file of files(root).filter(
    (file) => file.endsWith('.ts') && !file.endsWith('.spec.ts'),
  )) {
    assert.deepEqual(violations(readFileSync(file, 'utf8')), [], file);
  }
  const service = readFileSync(join(root, 'services/patient.service.ts'), 'utf8');
  for (const path of ['check', 'search'])
    assert.match(
      service,
      new RegExp(`http\\.post[\\s\\S]*?'/patients/national-unique-id/${path}'`),
    );
  const form = readFileSync(join(root, 'patients/patient-form.ts'), 'utf8');
  const edit = readFileSync(
    join(root, 'patients/patient-demographics-edit/patient-demographics-edit.component.ts'),
    'utf8',
  );
  // QA D2032: an unready identifier still blocks the save, and now says why.
  const refusesUnready =
    /if \(!this\.nationalUniqueIdReady\) \{[\s\S]*?refuseSubmit\(\)[\s\S]*?return;\s*\}/;
  assert.match(form, refusesUnready);
  assert.match(edit, refusesUnready);
  assert.match(form, /\.\.\.this\.nationalUniqueIdChange/);
  assert.match(edit, /\.\.\.this\.nationalUniqueIdChange/);
});
