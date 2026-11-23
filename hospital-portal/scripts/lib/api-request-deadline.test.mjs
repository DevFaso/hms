import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const portal = join(dirname(fileURLToPath(import.meta.url)), '../..');
const read = (p) => readFileSync(join(portal, p), 'utf8');
function files(root) {
  return readdirSync(root, { withFileTypes: true }).flatMap((e) => {
    const path = join(root, e.name);
    if (e.isDirectory()) return files(path);
    return path.endsWith('.ts') && !path.endsWith('.spec.ts') ? [path] : [];
  });
}
function bypasses(source, file) {
  const violations = [];
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
  const visit = (node) => {
    if (
      (ts.isCallExpression(node) || ts.isNewExpression(node)) &&
      ts.isIdentifier(node.expression)
    ) {
      const name = node.expression.text;
      if (['HttpClient', 'XMLHttpRequest', 'HttpBackend'].includes(name)) violations.push(name);
      if (name === 'provideHttpClient' && file !== 'src/app/app.config.ts') violations.push(name);
      // This one fetch probes index.html for chunk recovery, never an API.
      if (
        name === 'fetch' &&
        !(
          file === 'src/app/core/chunk-recovery.service.ts' &&
          node.arguments?.[0] &&
          ts.isStringLiteral(node.arguments[0]) &&
          node.arguments[0].text === '/index.html'
        )
      )
        violations.push(name);
    }
    if (ts.isImportSpecifier(node) && node.name.text === 'HttpBackend')
      violations.push('HttpBackend');
    ts.forEachChild(node, visit);
  };
  visit(ast);
  return violations;
}

test('D3602: every browser API caller inherits the shared deadline; no bypass clients', () => {
  assert.match(read('src/app/app.config.ts'), /withInterceptors\(\[\s*apiTimeoutInterceptor,/);
  const interceptor = read('src/app/interceptors/api-timeout.interceptor.ts');
  assert.match(interceptor, /takeUntil\(\s*timer\(deadline\)/);
  assert.match(interceptor, /API_REQUEST_TIMEOUT_MS\s*=\s*20_000/);
  assert.match(interceptor, /API_UPLOAD_TIMEOUT_MS\s*=\s*120_000/);
  for (const path of files(join(portal, 'src'))) {
    const file = relative(portal, path).replaceAll('\\', '/');
    assert.deepEqual(bypasses(readFileSync(path, 'utf8'), file), [], file);
  }
  assert.match(read('src/app/app.component.ts'), /<app-api-timeout-banner\s*\/>/);
  assert.match(
    read('src/app/billing/billing.ts'),
    /statsUnknown\s*=\s*computed\([\s\S]*?this\.loading\(\)[\s\S]*?this\.summaryLoading\(\)/,
  );
});

test('D3602: the class guard detects new routes creating bypass transports', () => {
  for (const source of [
    'fetch("/api/patients")',
    'new XMLHttpRequest()',
    'new HttpClient(handler)',
    'provideHttpClient()',
  ]) {
    assert.notDeepEqual(bypasses(source, 'src/app/new-view.ts'), []);
  }
  assert.deepEqual(bypasses('this.fetch(offset)', 'src/app/pager.ts'), []);
});
