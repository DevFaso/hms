import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { relative } from 'node:path';
import ts from 'typescript';
import { walk } from './walk.mjs';

const app = fileURLToPath(new URL('../../src/app/', import.meta.url));

function redirectOwner(source) {
  const ast = ts.createSourceFile('source.ts', source, ts.ScriptTarget.Latest, true);
  let found = false;
  function visit(node) {
    if (
      ts.isReturnStatement(node) &&
      node.expression &&
      ts.isStringLiteral(node.expression) &&
      node.expression.text === '/super-admin'
    )
      found = true;
    if (
      ts.isCallExpression(node) &&
      node.expression.getText(ast).endsWith('.parseUrl') &&
      node.arguments.some((arg) => ts.isStringLiteral(arg) && arg.text === '/super-admin')
    )
      found = true;
    if (ts.isFunctionDeclaration(node) && node.name?.text === 'superAdminPathRewriteGuard')
      found = true;
    ts.forEachChild(node, visit);
  }
  visit(ast);
  return found;
}

test('redirect-owner scan finds literal landing, guard redirects and dynamic legacy rewrite owners', () => {
  assert.equal(redirectOwner("function landing() { return '/super-admin'; }"), true);
  assert.equal(redirectOwner("const guard = () => router.parseUrl('/super-admin');"), true);
  assert.equal(
    redirectOwner(
      'function superAdminPathRewriteGuard(path) { return () => router.parseUrl(path); }',
    ),
    true,
  );
  assert.equal(redirectOwner("// return '/super-admin';\nconst item = '/super-admin';"), false);
});

test('all super-admin redirect owners use the shared session predicate', () => {
  const owners = walk(app, ['.ts'])
    .filter((file) => !file.endsWith('.spec.ts'))
    .filter((file) => redirectOwner(readFileSync(file, 'utf8')));
  assert.deepEqual(owners.map((file) => relative(app, file).replaceAll('\\', '/')).sort(), [
    'auth/auth.service.ts',
    'auth/super-admin-path-rewrite.guard.ts',
    'auth/super-admin-redirect.guard.ts',
  ]);
  for (const file of owners)
    assert.match(readFileSync(file, 'utf8'), /(?:this|auth)\.isActiveSuperAdmin\(\)/u, file);
  const auth = readFileSync(
    owners.find((file) => file.endsWith('auth.service.ts')),
    'utf8',
  );
  const ast = ts.createSourceFile('auth.ts', auth, ts.ScriptTarget.Latest, true);
  let predicate = '';
  function visit(node) {
    if (ts.isMethodDeclaration(node) && node.name.getText(ast) === 'isActiveSuperAdmin')
      predicate = node.body.getText(ast);
    ts.forEachChild(node, visit);
  }
  visit(ast);
  // The selected role decides; with none selected, the token's roles do.
  assert.match(predicate, /this\.roleContext\.activeRole/u);
  assert.match(predicate, /this\.hasAnyRole\(\['ROLE_SUPER_ADMIN'\]\)/u);
});
