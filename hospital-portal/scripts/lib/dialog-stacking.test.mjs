#!/usr/bin/env node
/**
 * QA D1122 guard (round 20): no dialog renders under its backdrop.
 *
 * Every template of the portal (each `.html` and each inline `template:`) is
 * read with its component's own styles; a dialog written as the SIBLING of
 * its backdrop must be lifted above it (lib/dialog-stacking.mjs). Allowlist:
 * none.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

import { stackingFindings, siblingCoverPanels, declarationsFor } from './dialog-stacking.mjs';
import { loadTemplates } from './template-gate.mjs';

const SRC = resolve(import.meta.dirname, '..', '..', 'src');
/** The global styles every component gets (angular.json `styles` and what it `@use`s). */
const GLOBAL_CSS = ['styles.scss', 'app/shared/styles/_page-common.scss']
  .map((p) => resolve(SRC, p))
  .filter((p) => existsSync(p))
  .map((p) => readFileSync(p, 'utf8'))
  .join('\n');

/** Global styles first, then the component's own: styleUrl(s), the sibling .scss/.css, inline `styles`. */
function stylesFor(file) {
  const ts = file.endsWith('.html') ? file.replace(/\.html$/, '.ts') : file;
  const source = existsSync(ts) ? readFileSync(ts, 'utf8') : '';
  const urls = [...source.matchAll(/styleUrls?\s*:\s*(\[[^\]]*\]|['"][^'"]+['"])/g)].flatMap((m) =>
    [...m[1].matchAll(/['"]([^'"]+)['"]/g)].map((u) => resolve(dirname(ts), u[1])),
  );
  const base = file.replace(/\.(html|ts)$/, '');
  const candidates = new Set([...urls, `${base}.scss`, `${base}.css`]);
  const css = [...candidates].filter((p) => existsSync(p)).map((p) => readFileSync(p, 'utf8'));
  return [GLOBAL_CSS, ...css, source].join('\n');
}

test('D1122: the "Libérer le lit" shape — a sibling dialog left in the page flow — is found', () => {
  const html = `@if (x) {
  <div class="modal-backdrop" tabindex="-1" (click)="close()"></div>
  <div class="modal" appModalDialog aria-labelledby="t" (escaped)="close()"><h2 id="t">T</h2></div>
}`;
  const css = `.modal-backdrop { position: fixed; inset: 0; display: flex; z-index: 1000; }
.modal { background: #fff; max-width: 520px; }`;
  const [finding] = stackingFindings(html, css);
  assert.equal(finding.line, 3);
  assert.match(finding.message, /position: static, z-index auto; cover z-index 1000/);
});

test('a dialog nested INSIDE its backdrop is above it by construction', () => {
  const html = `<div class="modal-backdrop" (click)="c()"><div class="modal" role="dialog"></div></div>`;
  assert.deepEqual(stackingFindings(html, '.modal-backdrop{position:fixed;z-index:1000}'), []);
});

test('a sibling panel with position: fixed and a higher z-index passes; an equal z-index does not', () => {
  const html = `<div class="drawer-overlay" (click)="c()"></div><aside class="drawer" appModalDialog></aside>`;
  assert.deepEqual(
    stackingFindings(
      html,
      '.drawer-overlay{position:fixed;z-index:400} .drawer{position:fixed;z-index:401}',
    ),
    [],
  );
  assert.equal(
    stackingFindings(
      html,
      '.drawer-overlay{position:fixed;z-index:400} .drawer{position:fixed;z-index:400}',
    ).length,
    1,
  );
});

test('declarationsFor reads only the rule whose subject is the class, without nested blocks', () => {
  const css = `.modal { position: fixed; z-index: 5; .modal-header { position: static; } }
.modal-header { z-index: 1 } .x .modal:hover { color: red }`;
  const decls = declarationsFor(css, 'modal');
  assert.equal(decls.length, 2);
  assert.match(decls[0], /position: fixed/);
  assert.doesNotMatch(decls[0], /static/);
});

test('every template: no dialog renders under its backdrop (allowlist: none)', () => {
  const templates = loadTemplates();
  let pairs = 0;
  const offenders = [];
  for (const { file, rel, text, lineOffset } of templates) {
    pairs += siblingCoverPanels(text).length;
    for (const f of stackingFindings(text, stylesFor(file))) {
      offenders.push(`${rel}:${f.line + lineOffset} ${f.anchor} — ${f.message}`);
    }
  }
  // The scan must keep seeing the sibling shape (the shell bell, the drawers, the bed board).
  assert.ok(pairs >= 5, `only ${pairs} sibling cover/panel pairs found: the scan lost its shape`);
  assert.deepEqual(offenders, []);
});
