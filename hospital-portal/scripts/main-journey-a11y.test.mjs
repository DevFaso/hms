import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { createRequire } from 'node:module';
import ts from 'typescript';
import vm from 'node:vm';
import { componentSources } from './lib/component-sources.mjs';

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

test('the CI axe entry point scans at least twenty-five real main routes with contrast enabled', () => {
  const entry = readFileSync('e2e/a11y.spec.ts', 'utf8');
  assert.match(entry, /import '\.\/a11y-routed-pages'/);
  assert.match(entry, /import '\.\/a11y-journey-forms'/);
  const routes = loadTypescript('e2e/helpers/routed-page-inventory.ts').routedPages();
  const main = loadTypescript('e2e/helpers/main-journey-routes.ts');
  assert.ok(new Set(main.MAIN_JOURNEY_ROUTES).size >= 25);
  for (const path of main.MAIN_JOURNEY_ROUTES) {
    const route = routes.find((candidate) => candidate.path === path);
    assert.ok(route, `main journey removed from the scanned inventory: ${path}`);
    const role = main.MAIN_JOURNEY_ROLES[path];
    if (role)
      assert.ok(route.roles.includes(role), `${path}: the scan must use an admitted working role`);
  }
  for (const filename of [
    'e2e/a11y.spec.ts',
    'e2e/a11y-routed-pages.ts',
    'e2e/a11y-journey-forms.ts',
  ]) {
    const source = readFileSync(filename, 'utf8');
    assert.doesNotMatch(source, /\.disableRules\s*\(/);
    assert.match(source, /toHaveURL/);
  }
});

test('every live required-field banner shares the contract: edits remove filled fields, preserve server errors and detach on close', () => {
  const LiveRequiredMessage = loadTypescript(
    'src/app/shared/required-fields.ts',
  ).LiveRequiredMessage;
  const consumers = componentSources().filter((source) =>
    source.text.includes('new LiveRequiredMessage('),
  );
  assert.ok(consumers.length > 0);
  for (const consumer of consumers) {
    const model = { hospital: '', date: '' };
    let shown = null;
    const banner = Object.assign(() => shown, {
      set: (value) => {
        shown = value;
      },
    });
    const translate = { instant: (key, params) => params?.fields ?? key };
    const root = new globalThis.EventTarget();
    const live = new LiveRequiredMessage(
      translate,
      () => [
        { labelKey: 'HOSPITAL', value: model.hospital },
        { labelKey: 'DATE', value: model.date },
      ],
      banner,
    );
    live.check(root);
    assert.equal(shown, 'HOSPITAL, DATE', consumer.file);
    model.hospital = 'cdx-hospital';
    root.dispatchEvent(new globalThis.Event('change'));
    assert.equal(shown, 'DATE', consumer.file);
    model.date = '2026-11-23';
    root.dispatchEvent(new globalThis.Event('input'));
    assert.equal(shown, null, consumer.file);
    model.date = '';
    live.check(root);
    banner.set('cdx-server-refusal');
    model.date = '2026-11-24';
    root.dispatchEvent(new globalThis.Event('input'));
    assert.equal(shown, 'cdx-server-refusal', consumer.file);
    live.stop();
  }
});

test('main-form refusal cases and real keyboard checkout/dispensing stay in the CI axe entry point', () => {
  const entry = readFileSync('e2e/a11y.spec.ts', 'utf8');
  assert.match(entry, /import '\.\/a11y-keyboard-care'/);
  assert.match(entry, /import '\.\/a11y-main-refusals'/);
  const forms = loadTypescript('e2e/helpers/main-form-journeys.ts').MAIN_FORM_JOURNEYS;
  const routes = loadTypescript('e2e/helpers/main-journey-routes.ts').MAIN_JOURNEY_ROUTES;
  assert.ok(forms.length >= 8);
  assert.equal(new Set(forms.map((form) => form.route)).size, forms.length);
  for (const form of forms)
    assert.ok(routes.includes(form.route), `Unscanned main form: ${form.route}`);
  const source = readFileSync('e2e/a11y-keyboard-care.ts', 'utf8');
  assert.match(source, /page\.goto\('\/billing'\)/);
  assert.match(source, /page\.goto\('\/pharmacy\/dispensing'\)/);
  for (const file of [
    'e2e/a11y-keyboard-care.ts',
    'e2e/helpers/keyboard-journey.ts',
    'e2e/a11y-main-refusals.ts',
  ]) {
    const code = readFileSync(file, 'utf8');
    const tree = ts.createSourceFile(file, code, ts.ScriptTarget.Latest, true);
    const visit = (node) => {
      if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression)) {
        assert.ok(
          !['click', 'dblclick', 'focus', 'fill', 'selectOption', 'check'].includes(
            node.expression.name.text,
          ),
          `${file}: keyboard journey bypasses the keyboard with ${node.expression.name.text}`,
        );
      }
      ts.forEachChild(node, visit);
    };
    visit(tree);
    assert.doesNotMatch(code, /\.disableRules\s*\(/);
  }
});

test('the existing logotype contrast exception never suppresses another node or another rule', () => {
  const { blockingAxeViolations } = loadTypescript('e2e/helpers/axe-blocking.ts');
  const brand = { html: '<span class="brand-accent">Keneya</span>' };
  const input = { html: '<input id="real-field">' };
  const rows = [
    { id: 'color-contrast', impact: 'serious', nodes: [brand] },
    { id: 'color-contrast', impact: 'serious', nodes: [brand, input] },
    { id: 'label', impact: 'critical', nodes: [brand] },
  ];
  const found = blockingAxeViolations(rows);
  assert.equal(found.length, 2);
  assert.deepEqual(JSON.parse(JSON.stringify(found[0].nodes)), [input]);
  assert.equal(found[1].id, 'label');
});
