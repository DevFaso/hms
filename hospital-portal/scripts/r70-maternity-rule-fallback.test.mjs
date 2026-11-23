import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import ts from 'typescript';

test('every midwife-rule fallback preserves its localized provisional approval disclaimer', () => {
  for (const language of ['en', 'fr', 'es']) {
    const messages = JSON.parse(
      readFileSync(new URL(`../src/assets/i18n/${language}.json`, import.meta.url), 'utf8'),
    ).MATERNITY_STAY;
    const disclaimer = messages.MIDWIFE_BED_RULE.split('. ').at(-1);
    assert.match(disclaimer, /provisional|provisoire/i);
    const rules = Object.entries(messages).filter(([key]) => /^MIDWIFE_.*_RULE$/.test(key));
    assert.ok(rules.length >= 3);
    for (const [key, value] of rules) {
      assert.ok(value.endsWith(disclaimer), `${language}: ${key} lost its approval disclaimer`);
    }
  }
});

test('optional hospital rule-status reads are covered by the shared refused-read handling', () => {
  const file = ts.createSourceFile(
    'error.interceptor.ts',
    readFileSync(new URL('../src/app/interceptors/error.interceptor.ts', import.meta.url), 'utf8'),
    ts.ScriptTarget.Latest,
    true,
  );
  const declarations = file.statements
    .filter(ts.isVariableStatement)
    .flatMap((statement) => [...statement.declarationList.declarations]);
  const declaration = declarations.find(
    (item) => item.name.getText(file) === 'SILENT_403_PATTERNS',
  );
  assert.ok(declaration?.initializer && ts.isArrayLiteralExpression(declaration.initializer));
  const patterns = declaration.initializer.elements
    .filter(ts.isRegularExpressionLiteral)
    .map((el) => {
      const literal = el.getText(file);
      const lastSlash = literal.lastIndexOf('/');
      return new RegExp(literal.slice(1, lastSlash), literal.slice(lastSlash + 1));
    });
  assert.ok(patterns.length > 5);
  for (const url of [
    '/hospitals/cdx/clinical-rules',
    '/api/hospitals/cdx/clinical-rules?view=labels',
  ]) {
    assert.ok(
      patterns.some((pattern) => pattern.test(url)),
      url,
    );
  }
});
