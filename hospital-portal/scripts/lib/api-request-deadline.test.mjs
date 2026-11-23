import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
// Execute the production selector, so a wrong budget cannot pass a source-only guard.
await import('@angular/compiler');
const { HttpRequest, HttpContext } = await import('@angular/common/http');
const { withApiTimeoutProfile, API_TIMEOUT_PROFILE, API_TIMEOUT_BUDGETS } =
  await import('../../src/app/core/api-request-timeout.ts');

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
  const policy = read('src/app/core/api-request-timeout.ts');
  assert.match(interceptor, /withApiTimeoutProfile\(req\)/);
  assert.match(policy, /new HttpContextToken/);
  assert.match(policy, /req\.body instanceof FormData/);
  assert.match(policy, /req\.responseType === 'blob'/);
  assert.match(policy, /API_EXPORT_TIMEOUT_MS\s*=\s*180_000/);
  assert.match(policy, /API_REQUEST_TIMEOUT_MS\s*=\s*20_000/);
  assert.match(policy, /API_UPLOAD_TIMEOUT_MS\s*=\s*120_000/);
  for (const path of files(join(portal, 'src'))) {
    const file = relative(portal, path).replaceAll('\\', '/');
    assert.deepEqual(bypasses(readFileSync(path, 'utf8'), file), [], file);
  }
  const auth = read('src/app/interceptors/error.interceptor.ts');
  assert.match(auth, /shareReplay\(\{ bufferSize: 1, refCount: false \}\)/);
  assert.match(auth, /takeUntil\(auth\.sessionEnded\$\)/);
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

test('the shared API boundary keeps every upload and export beyond the ordinary deadline', () => {
  const cases = [
    [new HttpRequest('GET', '/ordinary'), 'default', 20_000],
    [new HttpRequest('POST', '/multipart', new globalThis.FormData()), 'upload', 120_000],
    [new HttpRequest('POST', '/raw-file', new globalThis.Blob(['file'])), 'upload', 120_000],
    [new HttpRequest('GET', '/export.pdf', { responseType: 'blob' }), 'export', 180_000],
    [
      new HttpRequest('GET', '/json-export', {
        context: new HttpContext().set(API_TIMEOUT_PROFILE, 'export'),
      }),
      'export',
      180_000,
    ],
  ];
  for (const [request, profile, milliseconds] of cases) {
    const marked = withApiTimeoutProfile(request);
    assert.equal(marked.context.get(API_TIMEOUT_PROFILE), profile, request.url);
    assert.equal(API_TIMEOUT_BUDGETS[profile], milliseconds, request.url);
  }
});

test('JSON file imports and base64 exports explicitly select their transfer context', () => {
  const transfers = [
    ['src/app/services/super-admin-governance.service.ts', 'importUsers', 'upload'],
    ['src/app/services/assignment-admin.service.ts', 'bulkImport', 'upload'],
    ['src/app/services/super-admin-governance.service.ts', 'exportLatestBaseline', 'export'],
  ];
  for (const [file, method, profile] of transfers) {
    const source = read(file);
    const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
    let body;
    function find(node) {
      if (ts.isMethodDeclaration(node) && node.name.getText(ast) === method)
        body = node.body?.getText(ast);
      ts.forEachChild(node, find);
    }
    find(ast);
    assert.ok(body, file + '#' + method);
    assert.ok(
      body.includes(`new HttpContext().set(API_TIMEOUT_PROFILE, '${profile}')`),
      file + '#' + method,
    );
  }
  for (const file of files(join(portal, 'src/app/services'))) {
    const source = readFileSync(file, 'utf8');
    if (/\bcsvContent:\s*string/.test(source)) {
      const relativeFile = relative(portal, file).replaceAll('\\', '/');
      assert.ok(
        transfers.some(([path]) => path === relativeFile),
        relativeFile + ': JSON file import needs a transfer-context guard entry',
      );
    }
  }
});
