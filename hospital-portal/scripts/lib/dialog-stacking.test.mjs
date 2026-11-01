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
import { componentSources } from './component-sources.mjs';
import { nestedDialogPairs, wholeDialogSelectors } from './nested-dialog-scan.mjs';

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

// ── QA D1209 / D1201 (round 23): a dialog opened from INSIDE another dialog ──
//
// The D1201 fix replaced window.prompt with the shared reason dialog, and the
// reason dialog opened from the still-open Précautions dialog was painted under
// it (the bed board's `.modal` z-index 1001, the shared backdrop 1000). The
// D1122 rule above reads one template's own covers; it cannot see two dialogs
// from different components. The order is now set at run time by
// shared/a11y/overlay-stack.ts, which every dialog directive goes through.
//
// This guard (1) enumerates every pair of dialogs where one is opened from
// inside another that stays open (lib/nested-dialog-scan.mjs), (2) requires
// each pair to be listed below with the click-level spec that covers its
// shape — a NEW pair fails until someone checks it and names its spec, (3)
// requires both dialogs of every pair to be built on a dialog directive (the
// only thing the overlay stack lifts), and (4) pins the stack into the three
// directives, so removing it fails here before it fails on a screen.

const SPEC_BED_BOARD = 'src/app/bed-board/bed-board.spec.ts';
/**
 * The shapes, each clicked with the mouse: an appModalDialog modal, an
 * appDialogA11y side panel or an appDialogFocus parent, under the shared
 * reason / confirm dialog or an appModalDialog box, written as a sibling or
 * inside the parent.
 */
const SPEC_SHAPES = 'src/app/shared/a11y/overlay-stack.spec.ts';

/** Every dialog opened from inside another that stays open, and the spec that clicks its shape. */
const NESTED_DIALOGS = new Map([
  [
    "src/app/bed-board/bed-board.html::div.modal['BED_BOARD.PRECAUTIONS' | translate] => app-reason-dialog[BED_BOARD.DISCONTINUE_PROMPT]",
    SPEC_BED_BOARD,
  ],
  [
    'src/app/billing/billing.html::div.detail-panel[bill-detail-title] => div.modal[bill-adj-reversal-title]',
    SPEC_SHAPES,
  ],
  [
    'src/app/billing/billing.html::div.detail-panel[bill-detail-title] => div.modal[bill-reversal-title]',
    SPEC_SHAPES,
  ],
  [
    'src/app/encounters/encounters.html::div.detail-panel[encounters-panel-1-title] => app-checkout-dialog',
    SPEC_SHAPES,
  ],
  [
    'src/app/encounters/encounters.html::div.detail-panel[encounters-panel-1-title] => app-medical-certificate-dialog',
    SPEC_SHAPES,
  ],
  [
    'src/app/encounters/encounters.html::div.detail-panel[encounters-panel-1-title] => app-close-unseen-dialog',
    SPEC_SHAPES,
  ],
  [
    'src/app/imaging/imaging.html::div.detail-panel[imaging-panel-1-title] => div.modal[imaging-dialog-2-title]',
    SPEC_SHAPES,
  ],
  [
    'src/app/lab/lab-approval-queue/lab-approval-queue.html::div.detail-panel[lab-approval-dialog-title-1] => div.modal[lab-approval-dialog-title-2]',
    SPEC_SHAPES,
  ],
  [
    'src/app/lab/lab.html::div.modal[lab-dialog-title-4] => div.modal[lab-reject-title]',
    SPEC_SHAPES,
  ],
  [
    'src/app/patient-portal/my-education/my-education.component.html::div.booking-form-card[edu-dialog-title-1] => div.booking-form-card[edu-dialog-title-2]',
    SPEC_SHAPES,
  ],
  [
    "src/app/reception/patient-snapshot-drawer/patient-snapshot-drawer.component.html::div.drawer-panel[reception-snapshot-drawer-title] => div.attest-modal['RECEPTION.ATTEST_ELIGIBILITY' | translate]",
    SPEC_SHAPES,
  ],
  [
    'src/app/referrals/referrals.html::div.detail-panel[referrals-panel-1-title] => div.modal[referrals-dialog-2-title]',
    SPEC_SHAPES,
  ],
  [
    'src/app/referrals/referrals.html::div.detail-panel[referrals-panel-1-title] => div.modal[referrals-dialog-3-title]',
    SPEC_SHAPES,
  ],
  [
    'src/app/referrals/referrals.html::div.detail-panel[referrals-panel-1-title] => div.modal[referrals-dialog-4-title]',
    SPEC_SHAPES,
  ],
  [
    'src/app/referrals/referrals.html::div.detail-panel[referrals-panel-1-title] => div.modal[referrals-dialog-5-title]',
    SPEC_SHAPES,
  ],
  [
    'src/app/referrals/referrals.html::div.detail-panel[referrals-panel-1-title] => div.modal[referrals-dialog-6-title]',
    SPEC_SHAPES,
  ],
  [
    'src/app/transfusion/transfusion.html::div.detail-panel[transfusion-panel-1-title] => div.modal[transfusion-dialog-4-title]',
    SPEC_SHAPES,
  ],
  [
    'src/app/transfusion/transfusion.html::div.detail-panel[transfusion-panel-1-title] => div.modal[transfusion-dialog-5-title]',
    SPEC_SHAPES,
  ],
  [
    'src/app/transfusion/transfusion.html::div.modal[transfusion-dialog-1-title] => div.modal[transfusion-dialog-2-title]',
    SPEC_SHAPES,
  ],
]);

const PORTAL = resolve(import.meta.dirname, '..', '..');
const STACKED = /\b(?:appModalDialog|appDialogA11y|appDialogFocus)\b/;

function nestedPairs() {
  const components = componentSources();
  const templates = loadTemplates(components);
  const selectors = wholeDialogSelectors(components, templates);
  const templateOf = new Map();
  for (const t of templates) {
    const ts = t.rel.endsWith('.html') ? t.rel.replace(/\.html$/, '.ts') : t.rel;
    templateOf.set(ts, `${templateOf.get(ts) ?? ''}\n${t.text}`);
  }
  const selectorTemplate = new Map();
  for (const { file, text } of components) {
    const sel = /selector:\s*['"]([a-z][\w-]*)['"]/.exec(text);
    if (sel && selectors.has(sel[1])) selectorTemplate.set(sel[1], templateOf.get(file) ?? '');
  }
  const out = [];
  for (const t of templates) {
    for (const p of nestedDialogPairs(t.text, t.componentSource, selectors)) {
      out.push({ ...p, rel: t.rel, id: `${t.rel}::${p.parent} => ${p.child}` });
    }
  }
  return { out, selectorTemplate };
}

/** An end the overlay stack lifts: the scan's verdict, or for a child component its template's. */
function sideIsStacked(side, verdict, selectorTemplate) {
  if (verdict !== null) return verdict;
  const sel = /^([a-z][\w-]*)/.exec(side)[1];
  return STACKED.test(selectorTemplate.get(sel) ?? '');
}

test('D1209: the nested-dialog scan finds the bed-board shape and refuses a hand-off', () => {
  const sel = new Set(['app-reason-dialog']);
  const html = `@if (open()) {
  <div class="modal" appModalDialog aria-label="P"><button (click)="ask(p)">Lever</button>
    <button (click)="next()">Next</button></div>
}
@if (asking(); as p) { <app-reason-dialog titleKey="T" /> }
@if (other()) { <div appModalDialog aria-label="O"></div> }`;
  const ts = `ask(p) { this.asking.set(p); }
  next() { this.open.set(false); this.other.set(true); }`;
  const pairs = nestedDialogPairs(html, `${html}\n${ts}`, sel);
  assert.deepEqual(
    pairs.map((p) => `${p.parent} => ${p.child}`),
    ['div.modal => app-reason-dialog[T]'],
  );
});

test('D1209: a refresh after the server answered is not an open', () => {
  const html = `@if (sel(); as s) { <div class="panel" appDialogA11y aria-label="P"><button (click)="pay()">Pay</button></div> }
@if (paying()) { <div class="modal" appModalDialog aria-label="M"></div> }`;
  const ts = `pay() { this.api.pay().subscribe(() => { this.paying.set(true); }); }`;
  assert.deepEqual(nestedDialogPairs(html, `${html}\n${ts}`), []);
});

test('D1209: every dialog opened from inside a dialog is listed with its click spec, and goes through the overlay stack', () => {
  const { out, selectorTemplate } = nestedPairs();
  const found = new Set(out.map((p) => p.id));
  const unlisted = out
    .filter((p) => !NESTED_DIALOGS.has(p.id))
    .map((p) => `${p.rel}:${p.parentLine} → :${p.childLine} ${p.id}`);
  assert.deepEqual(
    unlisted,
    [],
    'a NEW dialog opens from inside another: click it with testing/mouse-click.ts in a spec and list it here',
  );
  const stale = [...NESTED_DIALOGS.keys()].filter((id) => !found.has(id));
  assert.deepEqual(stale, [], 'listed pairs the scan no longer finds: delete them');
  for (const spec of new Set(NESTED_DIALOGS.values())) {
    assert.ok(existsSync(resolve(PORTAL, spec)), `${spec} is missing`);
    assert.match(
      readFileSync(resolve(PORTAL, spec), 'utf8'),
      /mouseClick\(/,
      `${spec} must click with the mouse`,
    );
  }
  // Both ends are built on a dialog directive (or are a native modal
  // <dialog>, in the top layer): what the overlay stack lifts.
  const bare = out.flatMap((p) =>
    [
      [p.parent, p.parentStacked],
      [p.child, p.childStacked],
    ]
      .filter(([side, verdict]) => !sideIsStacked(side, verdict, selectorTemplate))
      .map(([side]) => `${p.id}: ${side}`),
  );
  assert.deepEqual(bare, []);
  assert.ok(out.length >= 15, `only ${out.length} nested pairs found: the scan lost its shape`);
});

test('D1209: every dialog directive opens, raises and closes a layer of the one overlay stack', () => {
  for (const name of ['modal-dialog', 'dialog-a11y', 'dialog-focus']) {
    const src = readFileSync(resolve(SRC, 'app', 'shared', 'a11y', `${name}.directive.ts`), 'utf8');
    assert.match(src, /new OverlayLayer\(/, `${name}: no overlay layer`);
    assert.match(src, /\.raise\(\)/, `${name}: the layer is never raised`);
    assert.match(src, /\.close\(\)/, `${name}: the layer is never closed`);
    assert.match(src, /\.isTop\(\)/, `${name}: the keyboard does not follow the one stack`);
    assert.doesNotMatch(src, /static readonly stack/, `${name}: a directive-specific stack is back`);
  }
});
