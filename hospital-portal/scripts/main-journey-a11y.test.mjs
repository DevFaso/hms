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
