import { test } from 'node:test';
import assert from 'node:assert/strict';
import { legacyMountFindings } from './check-clinical-form-controls.mjs';

test('unmounted legacy form exception fails as soon as a template or route uses it', () => {
  assert.deepEqual(legacyMountFindings([], []), []);
  assert.equal(
    legacyMountFindings([{ rel: 'page.html', text: '<app-cash-closing />' }], []).length,
    1,
  );
  assert.equal(
    legacyMountFindings(
      [],
      [{ file: 'src/app/routes.ts', text: 'component: CashClosingComponent' }],
    ).length,
    1,
  );
  assert.deepEqual(
    legacyMountFindings(
      [],
      [{ file: 'src/app/testing/registry.ts', text: 'CashClosingComponent' }],
    ),
    [],
  );
});
