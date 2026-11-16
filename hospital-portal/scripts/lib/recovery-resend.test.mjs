import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { walk } from './walk.mjs';

function resetsBeforeResend(source) {
  const ast = ts.createSourceFile('source.ts', source, ts.ScriptTarget.Latest, true);
  const found = [];
  function visit(node) {
    if (
      ts.isMethodDeclaration(node) &&
      node.body?.getText(ast).includes('.sendRecoveryVerificationCode(')
    ) {
      const request = node.body.getText(ast).indexOf('.sendRecoveryVerificationCode(');
      function reset(child) {
        if (
          ts.isCallExpression(child) &&
          child.expression.getText(ast).endsWith('.verificationCodeSent.set') &&
          child.arguments[0]?.kind === ts.SyntaxKind.FalseKeyword &&
          child.getStart(ast) - node.body.getStart(ast) < request
        ) {
          let parent = child.parent;
          let newContact = false;
          while (parent && parent !== node) {
            if (
              ts.isIfStatement(parent) &&
              /verifyingContactId\(\)\s*!==\s*contact\.id/u.test(parent.expression.getText(ast))
            )
              newContact = true;
            parent = parent.parent;
          }
          if (!newContact) found.push(node.name.getText(ast));
        }
        ts.forEachChild(child, reset);
      }
      reset(node.body);
    }
    ts.forEachChild(node, visit);
  }
  visit(ast);
  return found;
}

test('D2005 scan distinguishes a fresh contact from clearing a live challenge on every resend', () => {
  assert.deepEqual(
    resetsBeforeResend(
      'class C { send(contact) { this.verificationCodeSent.set(false); this.api.sendRecoveryVerificationCode(contact.id); }}',
    ),
    ['send'],
  );
  assert.deepEqual(
    resetsBeforeResend(
      'class C { send(contact) { if (this.verifyingContactId() !== contact.id) { this.verificationCodeSent.set(false); } this.api.sendRecoveryVerificationCode(contact.id); }}',
    ),
    [],
  );
});

test('every recovery-code caller preserves an accepted challenge during resend', () => {
  const app = fileURLToPath(new URL('../../src/app/', import.meta.url));
  const callers = walk(app, ['.ts'])
    .filter((file) => !file.endsWith('.spec.ts'))
    .filter((file) => readFileSync(file, 'utf8').includes('.sendRecoveryVerificationCode('));
  assert.equal(callers.length, 1, 'review every added caller for the resend state contract');
  assert.ok(callers[0].replaceAll('\\', '/').endsWith('/profile/profile.ts'));
  for (const file of callers)
    assert.deepEqual(resetsBeforeResend(readFileSync(file, 'utf8')), [], file);
});
