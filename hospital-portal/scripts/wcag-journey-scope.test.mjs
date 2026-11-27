import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { createRequire } from 'node:module';
import ts from 'typescript';
import vm from 'node:vm';

/**
 * Go-live P5: the agreed WCAG 2.1 AA scope (docs/qa/wcag-scope.md) and the
 * suite that enforces it (e2e/wcag-scope.spec.ts) cannot drift apart, and the
 * structural fixes the first run needed cannot come back.
 */

const require = createRequire(import.meta.url);
function loadTypescript(filename) {
  const exports = {};
  const compiled = ts.transpileModule(readFileSync(filename, 'utf8'), {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
      esModuleInterop: true,
    },
  }).outputText;
  vm.runInNewContext(compiled, { exports, require, __dirname: dirname(resolve(filename)) });
  return exports;
}

const scope = loadTypescript('e2e/helpers/wcag-journey-pages.ts');
const routes = loadTypescript('e2e/helpers/routed-page-inventory.ts').routedPages();

test('the scope document and the scanned pages list the same pages, journey by journey', () => {
  const doc = readFileSync('../docs/qa/wcag-scope.md', 'utf8');
  const section = doc.slice(doc.indexOf('## 3. Pages in scope'), doc.indexOf('## 4.'));
  const documented = [];
  for (const row of section.split('\n')) {
    const journey = /^\| (J\d+) /.exec(row)?.[1];
    if (!journey) continue;
    const pages = row.split('|').at(-2);
    for (const match of pages.matchAll(/`(\/[^`]*)`/g)) documented.push(`${journey} ${match[1]}`);
  }
  const scanned = scope.WCAG_JOURNEY_PAGES.map((page) => `${page.journey} ${page.route}`);
  assert.ok(scanned.length >= 30, 'the scope lost most of its pages');
  assert.deepEqual([...documented].sort(), [...scanned].sort());
  assert.equal(new Set(scanned).size, scanned.length, 'a page is listed twice');
});

test('every scope page is a real route, scanned as a role that route admits, at the three supported widths', () => {
  assert.equal(scope.WCAG_WIDTHS.join(' '), '390 768 1440');
  for (const page of scope.WCAG_JOURNEY_PAGES) {
    const route = routes.find((candidate) => candidate.path === page.route);
    assert.ok(route, `${page.route}: not a routed page any more`);
    if (page.role)
      assert.ok(
        route.roles.includes(page.role),
        `${page.route}: ${page.role} is not admitted by the route`,
      );
    else assert.equal(route.roles.length, 0, `${page.route}: a role-gated page scanned signed out`);
  }
});

test('the suite is wired into CI and suppresses no rule', () => {
  const spec = readFileSync('e2e/wcag-scope.spec.ts', 'utf8');
  assert.doesNotMatch(spec, /\.disableRules\s*\(|enabled:\s*false/);
  assert.match(spec, /toHaveURL/);
  for (const rule of ['landmark-one-main', 'landmark-unique', 'region', 'target-size'])
    assert.ok(spec.includes(`'${rule}'`), `the suite no longer runs ${rule}`);
  assert.match(
    JSON.parse(readFileSync('package.json', 'utf8')).scripts['e2e:wcag-scope'],
    /playwright\.wcag-scope\.config\.ts/,
  );
  assert.match(
    readFileSync('../.github/workflows/frontend-ci.yml', 'utf8'),
    /npm run e2e:wcag-scope/,
  );
});

test('a pending structure rule names the open change that owes it', () => {
  const pending = scope.WCAG_JOURNEY_PAGES.filter((page) => page.pending);
  // Shrinks only: /account-setup, until #1345 (which edits its template) merges.
  assert.equal(pending.map((page) => page.route).join(' '), '/account-setup');
  for (const page of pending) assert.match(page.pending.owner, /^#\d+$/);
});

test('every page outside the shell carries its own main landmark', () => {
  // The shell gives its child pages <main id="main-content">. A page routed
  // beside the shell (sign-in, second factor, reset, verification, privacy,
  // welcome) has to bring its own, or a screen reader finds no main region.
  const source = ts.createSourceFile(
    'app.routes.ts',
    readFileSync('src/app/app.routes.ts', 'utf8'),
    ts.ScriptTarget.Latest,
    true,
  );
  let top;
  source.forEachChild((node) => {
    if (!ts.isVariableStatement(node)) return;
    for (const declaration of node.declarationList.declarations)
      if (declaration.name.getText(source) === 'routes') top = declaration.initializer;
  });
  assert.ok(top && ts.isArrayLiteralExpression(top));
  const missing = [];
  let checked = 0;
  for (const route of top.elements) {
    if (!ts.isObjectLiteralExpression(route)) continue;
    const property = (key) =>
      route.properties.find(
        (entry) => ts.isPropertyAssignment(entry) && entry.name.getText(source) === key,
      )?.initializer;
    const load = property('loadComponent');
    if (!load || property('children')) continue;
    const path = property('path').getText(source).slice(1, -1);
    const module = /import\('\.\/([^']+)'\)/.exec(load.getText(source))?.[1];
    assert.ok(module, `unreadable loadComponent on ${path}`);
    const component = readFileSync(`src/app/${module}.ts`, 'utf8');
    const templateUrl = /templateUrl:\s*'\.\/([^']+)'/.exec(component)?.[1];
    const template = templateUrl
      ? readFileSync(resolve('src/app', dirname(module), templateUrl), 'utf8')
      : component;
    checked++;
    if (!/<main[\s>]/.test(template)) missing.push(path);
  }
  assert.ok(checked >= 7, 'the routes outside the shell were not found');
  // Shrinks only: account-setup.html belongs to #1345 while it is open.
  assert.deepEqual(missing, ['account-setup']);
});

test('a closed phone drawer leaves the Tab order', () => {
  // Moved off screen by a transform alone, the drawer's toggle and its menu
  // links still took focus, invisibly (SC 2.4.3, 2.4.7).
  const scss = readFileSync('src/app/shell/shell.scss', 'utf8').replaceAll('\r\n', '\n');
  const mobile = scss.slice(scss.indexOf('// ---------- Mobile ----------'));
  const drawer = /\n {2}\.sidebar \{\n([\s\S]*?)\n {2}\}\n/.exec(mobile)?.[1];
  assert.ok(drawer, 'the phone drawer rule was not found');
  const [closed, open] = drawer.split('.shell:not(.collapsed) & {');
  assert.match(closed, /transform: translateX\(-100%\)/);
  assert.match(closed, /visibility: hidden/);
  assert.match(open, /visibility: visible/);
});
