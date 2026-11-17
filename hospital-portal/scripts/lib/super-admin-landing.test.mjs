import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { relative, join } from 'node:path';
import ts from 'typescript';
import { walk } from './walk.mjs';

const app = fileURLToPath(new URL('../../src/app/', import.meta.url));

function retainsSelectedRole(source) {
  const ast = ts.createSourceFile('source.ts', source, ts.ScriptTarget.Latest, true);
  let retained = false;
  function visit(node) {
    if (
      ts.isVariableDeclaration(node) &&
      node.name.getText(ast) === 'roles' &&
      node.initializer &&
      ts.isConditionalExpression(node.initializer)
    ) {
      const choice = node.initializer;
      retained ||=
        choice.condition.getText(ast).includes("bootstrap.authSource === 'internal'") &&
        choice.whenTrue.getText(ast) === 'tokenRoles';
    }
    ts.forEachChild(node, visit);
  }
  visit(ast);
  return retained;
}

test('D1973: scan rejects replacing selected internal roles with account assignments', () => {
  assert.equal(
    retainsSelectedRole('const roles = bootstrap.roles?.length ? bootstrap.roles : tokenRoles;'),
    false,
  );
  assert.equal(
    retainsSelectedRole(
      "const roles = bootstrap.authSource === 'internal' && tokenRoles.length ? tokenRoles : bootstrap.roles;",
    ),
    true,
  );
});

test('all session-role writers are reviewed and bootstrap retains the selected internal roles', () => {
  const writers = [];
  for (const file of walk(app, ['.ts']).filter((path) => !path.endsWith('.spec.ts'))) {
    const source = readFileSync(file, 'utf8');
    const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
    let writes = false;
    function visit(node) {
      if (ts.isCallExpression(node) && node.expression.getText(ast).endsWith('.setRoles'))
        writes = true;
      ts.forEachChild(node, visit);
    }
    visit(ast);
    if (writes) writers.push(relative(app, file).replaceAll(String.fromCharCode(92), '/'));
  }
  assert.deepEqual(writers.sort(), [
    'app.component.ts',
    'auth/oidc-auth.service.ts',
    'core/session-scope.service.ts',
    'login/login.ts',
    'mfa/mfa-challenge.ts',
    'services/impersonation.service.ts',
  ]);
  assert.equal(
    retainsSelectedRole(readFileSync(join(app, 'core/session-scope.service.ts'), 'utf8')),
    true,
  );
  const context = readFileSync(join(app, 'core/role-context.service.ts'), 'utf8');
  assert.ok(context.includes('!roles.includes(active)'));
  assert.ok(context.includes("!roles.includes('ROLE_SUPER_ADMIN')"));
  assert.ok(context.includes('this._globalView.set(false)'));
  const impersonation = readFileSync(join(app, 'services/impersonation.service.ts'), 'utf8');
  assert.ok(!impersonation.includes('setRoles(profile.roles'));
});
