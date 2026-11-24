import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
// Execute the production selector, so a wrong budget cannot pass a source-only guard.
await import('@angular/compiler');
const { HttpRequest, HttpContext, HttpContextToken, HttpErrorResponse } =
  await import('@angular/common/http');
const {
  withApiTimeoutProfile,
  API_TIMEOUT_PROFILE,
  API_TIMEOUT_BUDGETS,
  isRefreshRejection,
  refreshFailureForCaller,
  timeoutNoticeMethod,
} = await import('../../src/app/core/api-request-timeout.ts');

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
    if (ts.isCallExpression(node) || ts.isNewExpression(node)) {
      const callee = node.expression;
      const globalProperty =
        ts.isPropertyAccessExpression(callee) &&
        ts.isIdentifier(callee.expression) &&
        ['window', 'globalThis', 'navigator', 'self'].includes(callee.expression.text);
      const name = ts.isIdentifier(callee) ? callee.text : globalProperty ? callee.name.text : null;
      if (
        ['HttpClient', 'XMLHttpRequest', 'HttpBackend', 'EventSource', 'sendBeacon'].includes(name)
      )
        violations.push(name);
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
    'window.fetch("/api/patients")',
    'globalThis.fetch("/api/patients")',
    'navigator.sendBeacon("/api/patients", "data")',
    'new EventSource("/api/patients")',
    'new globalThis.XMLHttpRequest()',
    'window.fetch("/api/patients")',
    'globalThis.fetch("/api/patients")',
    'navigator.sendBeacon("/api/patients", data)',
    'new EventSource("/api/stream")',
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
    [new HttpRequest('GET', '/export.bin', { responseType: 'arraybuffer' }), 'export', 180_000],
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

test('shared caller contexts never retain a previously inferred request budget', () => {
  const flag = new HttpContextToken(() => '');
  const context = new HttpContext().set(flag, 'hospital-a');
  for (const responseType of ['json', 'blob', 'json', 'arraybuffer', 'json']) {
    const request = withApiTimeoutProfile(
      new HttpRequest('GET', '/shared', { context, responseType }),
    );
    assert.equal(
      request.context.get(API_TIMEOUT_PROFILE),
      responseType === 'json' ? 'default' : 'export',
    );
    assert.equal(request.context.get(flag), 'hospital-a');
    assert.equal(context.has(API_TIMEOUT_PROFILE), false);
  }
});

test('only an explicit refresh rejection ends a session; session protocol POSTs are not saved user actions', () => {
  for (const status of [0, 409, 500, 502, 503, 504])
    assert.equal(isRefreshRejection(new HttpErrorResponse({ status })), false);
  assert.equal(isRefreshRejection(new Error('timeout')), false);
  for (const status of [400, 401, 403, 410])
    assert.equal(isRefreshRejection(new HttpErrorResponse({ status })), true);
  for (const path of ['login', 'token/refresh', 'mfa/verify', 'verify-password'])
    assert.equal(timeoutNoticeMethod(new HttpRequest('POST', '/api/auth/' + path, {})), 'GET');
  assert.equal(timeoutNoticeMethod(new HttpRequest('POST', '/api/auth/logout', {})), null);
  assert.equal(timeoutNoticeMethod(new HttpRequest('POST', '/api/invoices', {})), 'POST');
  assert.equal(
    timeoutNoticeMethod(new HttpRequest('POST', '/api/auth/me/change-password', {})),
    'POST',
  );
  const auth = read('src/app/interceptors/error.interceptor.ts');
  assert.match(auth, /if \(isRefreshRejection\(error\)\)/);
  assert.match(auth, /const callerError = refreshFailureForCaller\(error\)/);
  assert.match(auth, /refreshDone\$\.next\(\{ error: callerError \}\)/);
  assert.match(auth, /return throwError\(\(\) => callerError\)/);
  assert.equal(
    (
      auth.match(
        /!credentialCheck\s*&&\s*error instanceof HttpErrorResponse\s*&&\s*error\.status === 401/g,
      ) ?? []
    ).length,
    2,
  );
});

test('refresh protocol errors cannot be interpreted as invoice or other resource responses', () => {
  for (const status of [0, 404, 408, 409, 422, 429, 500, 502, 503, 504]) {
    const error = new HttpErrorResponse({ status, error: { message: 'protocol-only' } });
    const failure = refreshFailureForCaller(error);
    assert.equal(failure.status, 503);
    assert.deepEqual(failure.error, { code: 'SESSION_REFRESH_UNAVAILABLE' });
  }
  for (const status of [400, 401, 403, 410]) {
    const error = new HttpErrorResponse({ status });
    assert.equal(refreshFailureForCaller(error), error);
  }
  const timeout = new Error('timeout');
  assert.equal(refreshFailureForCaller(timeout), timeout);
});

test('timeout notices are scoped to the session and billing callbacks to the latest read', () => {
  const notice = read('src/app/core/api-timeout.service.ts');
  assert.match(notice, /if \(epoch !== this\.sessionEpoch\) return/);
  assert.match(notice, /resetForSession\(\)[\s\S]*?this\.failure\.set\(null\)/);
  const auth = read('src/app/auth/auth.service.ts');
  assert.match(auth, /private endSession\(\)[\s\S]*?this\.timeouts\.resetForSession\(\)/);
  assert.match(auth, /logout\(\): void \{\s*this\.endSession\(\)/);
  assert.match(auth, /getUserProfile\(\)\?\.id !== profile\.id/);
  const interceptor = read('src/app/interceptors/api-timeout.interceptor.ts');
  assert.match(interceptor, /notice\.noteTimeout\(method, sessionEpoch, request\.url\)/);
  assert.match(interceptor, /isSignInRequest\(request\)[\s\S]*?notice\.resetForSession\(\)/);
  const billing = read('src/app/billing/billing.ts');
  assert.equal(
    (billing.match(/if \(requestId !== this\.summaryGeneration\) return/g) ?? []).length,
    2,
  );
  assert.equal(
    (billing.match(/if \(requestId !== this\.listGeneration\) return/g) ?? []).length,
    3,
  );
  assert.match(billing, /loadInvoices\(page = 0\)[\s\S]*?\+\+this\.summaryGeneration/);
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

test('every text or arraybuffer response is an audited transfer or an ordinary delete acknowledgement', () => {
  const actual = [];
  for (const path of files(join(portal, 'src'))) {
    const file = relative(portal, path).replaceAll('\\', '/');
    const ast = ts.createSourceFile(file, readFileSync(path, 'utf8'), ts.ScriptTarget.Latest, true);
    const visit = (node) => {
      if (
        ts.isPropertyAssignment(node) &&
        node.name.getText(ast).replaceAll(/['"]/g, '') === 'responseType'
      ) {
        let value = node.initializer;
        while (ts.isAsExpression(value) || ts.isParenthesizedExpression(value))
          value = value.expression;
        if (ts.isStringLiteral(value) && ['text', 'arraybuffer'].includes(value.text)) {
          let caller = node.parent;
          while (caller && !ts.isCallExpression(caller)) caller = caller.parent;
          const method =
            caller && ts.isPropertyAccessExpression(caller.expression)
              ? caller.expression.name.text
              : 'configuration';
          actual.push(`${file}:${value.text}:${method}`);
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(ast);
  }
  assert.deepEqual(actual.sort(), [
    'src/app/services/billing.service.ts:text:delete',
    'src/app/services/billing.service.ts:text:delete',
    'src/app/services/lab.service.ts:text:delete',
    'src/app/services/patient-insurance.service.ts:text:delete',
  ]);
});

test('automatic retry operators remain limited to reviewed refresh and keyed-payment policies', () => {
  const actual = [];
  for (const path of files(join(portal, 'src'))) {
    const file = relative(portal, path).replaceAll('\\', '/');
    const ast = ts.createSourceFile(file, readFileSync(path, 'utf8'), ts.ScriptTarget.Latest, true);
    const aliases = new Set();
    const namespaces = new Set();
    for (const statement of ast.statements) {
      if (
        !ts.isImportDeclaration(statement) ||
        !ts.isStringLiteral(statement.moduleSpecifier) ||
        !['rxjs', 'rxjs/operators'].includes(statement.moduleSpecifier.text)
      )
        continue;
      const bindings = statement.importClause?.namedBindings;
      if (bindings && ts.isNamespaceImport(bindings)) namespaces.add(bindings.name.text);
      if (bindings && ts.isNamedImports(bindings)) {
        for (const item of bindings.elements)
          if (['retry', 'retryWhen'].includes((item.propertyName ?? item.name).text))
            aliases.add(item.name.text);
      }
    }
    const visit = (node) => {
      if (ts.isCallExpression(node)) {
        const target = node.expression;
        if (ts.isIdentifier(target) && aliases.has(target.text)) actual.push(file);
        if (
          ts.isPropertyAccessExpression(target) &&
          ts.isIdentifier(target.expression) &&
          namespaces.has(target.expression.text) &&
          ['retry', 'retryWhen'].includes(target.name.text)
        )
          actual.push(file);
      }
      ts.forEachChild(node, visit);
    };
    visit(ast);
  }
  assert.deepEqual(actual.sort(), [
    'src/app/interceptors/error.interceptor.ts',
    'src/app/shared/payment-request-key.ts',
  ]);
  const sql = readFileSync(
    join(
      portal,
      '../hospital-core/src/main/resources/db/migration/V310__payment_client_request_id.sql',
    ),
    'utf8',
  );
  for (const [index, table] of [
    ['uk_pt_hospital_client_request', 'billing.payment_transactions'],
    ['uk_pp_hospital_client_request', 'billing.pharmacy_payments'],
    ['uk_psale_hospital_client_request', 'clinical.pharmacy_sales'],
  ]) {
    assert.ok(sql.includes(`CREATE UNIQUE INDEX IF NOT EXISTS ${index}`));
    assert.ok(sql.includes(`ON ${table} (hospital_id, client_request_id)`));
  }
  const dispense = readFileSync(
    join(
      portal,
      '../hospital-core/src/main/resources/db/migration/V94__dispense_idempotency_key.sql',
    ),
    'utf8',
  );
  assert.match(dispense, /CREATE UNIQUE INDEX IF NOT EXISTS uq_disp_idempotency_key/);
  assert.match(dispense, /ON clinical\.dispenses \(idempotency_key\)/);
  assert.match(
    read('src/app/pharmacy/offline-dispense-queue.service.ts'),
    /request: \{ \.\.\.request, idempotencyKey: id \}/,
  );
});
