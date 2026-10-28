#!/usr/bin/env node
/**
 * The dialog-semantics scan (QA D976, D988, D1004). Each case is a shape from
 * the round-16 report, plus the house shape that must pass.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { dialogFindings } from './dialog-scan.mjs';

const rules = (html, source, selectors) =>
  dialogFindings(html, source, selectors)
    .map((f) => f.rule)
    .sort();

test('D976: a <div class="modal"> with no role is a dialog container without semantics', () => {
  const html = `<div class="modal-backdrop" (click)="close()">
    <div class="modal modal-lg" (click)="$event.stopPropagation()">
      <h3>{{ 'BILLING.RECORD_PAYMENT' | translate }}</h3>
    </div>
  </div>`;
  const [finding] = dialogFindings(html);
  assert.equal(finding.rule, 'role');
  assert.equal(finding.anchor, "modal modal-lg <{{ 'BILLING.RECORD_PAYMENT' | translate }}>");
  assert.equal(finding.line, 2);
});

test('D988: role="dialog" without aria-modal, a name or an Escape handler fails three ways', () => {
  const html = `<div class="checkin-dialog" role="dialog" tabindex="-1"><h2>Check-in</h2></div>`;
  assert.deepEqual(rules(html, html), ['escape', 'label', 'modal']);
});

test('the house shape passes: role, aria-modal, a translated name and (keydown.escape)', () => {
  const html = `<div
      class="dialog"
      role="dialog"
      aria-modal="true"
      [attr.aria-label]="'RECEPTION.REGISTER_WALKIN' | translate"
      (keydown.escape)="close()"
    >
      <h2>{{ 'RECEPTION.REGISTER_WALKIN' | translate }}</h2>
    </div>`;
  assert.deepEqual(dialogFindings(html), []);
});

test('aria-labelledby names the dialog, and an Escape handler in the component counts', () => {
  const html = `<div class="modal" role="dialog" aria-modal="true" aria-labelledby="t"><h2 id="t">T</h2></div>`;
  const ts = `@HostListener('document:keydown.escape') onEscape() { this.close(); }`;
  assert.deepEqual(dialogFindings(html, `${html}\n${ts}`), []);
  assert.deepEqual(rules(html, html), ['escape']);
});

test('a container inside, or wrapping, a role="dialog" element is not reported again', () => {
  const html = `<div class="modal" role="dialog" aria-modal="true" aria-label="x" (keydown.escape)="c()">
      <div class="modal-dialog"><div class="modal-content">body</div></div>
    </div>
    <div class="modal-wrapper" (keydown.escape)="c()"><div class="x-dialog" role="alertdialog" aria-modal="true" aria-label="y"></div></div>`;
  assert.deepEqual(dialogFindings(html), []);
});

test('D976: an Escape handler on a SIBLING backdrop never fires while focus is inside the dialog', () => {
  const html = `<div class="modal-backdrop" tabindex="-1" (click)="close()" (keydown.escape)="close()"></div>
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="t"><h2 id="t">T</h2></div>`;
  assert.deepEqual(rules(html, html), ['escape']);
  const nested = `<div class="modal-backdrop" (click)="close()" (keydown.escape)="close()">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="t"><h2 id="t">T</h2></div></div>`;
  assert.deepEqual(dialogFindings(nested), []);
  const keydown = `<div class="modal" role="dialog" aria-modal="true" aria-label="x" (keydown)="onKey($event)"></div>`;
  assert.deepEqual(
    dialogFindings(
      keydown,
      `${keydown}\nonKey(event: KeyboardEvent): void { if (event.key === 'Escape') this.close(); }`,
    ),
    [],
  );
  // The (keydown) handler must itself read 'Escape': a typeahead's Escape check
  // elsewhere in the component does not close this dialog.
  assert.deepEqual(
    rules(
      keydown,
      `${keydown}\nonKey(event: KeyboardEvent) { if (event.key === 'Enter') this.save(); }\n` +
        `onTypeaheadKey(e: KeyboardEvent) { if (e.key === 'Escape') this.closeTypeahead(); }`,
    ),
    ['escape'],
  );
  // A focus trap or an 'Escape' string elsewhere in the file is not a handler on this dialog.
  const bare = `<div class="modal" role="dialog" aria-modal="true" aria-label="x"></div>`;
  assert.deepEqual(
    rules(bare, `${bare}\ncdkTrapFocus; if (e.key === 'Escape') this.closeTypeahead();`),
    ['escape'],
  );
});

test('a container that only wraps a child component which is itself a dialog delegates', () => {
  const html = `<div class="modal"><app-order-set-picker (closed)="c()"></app-order-set-picker></div>`;
  assert.deepEqual(rules(html, html, new Set(['app-order-set-picker'])), []);
  assert.deepEqual(rules(html, html, new Set()), ['role']);
  // A wrapper around a dialog whose role is BOUND is a wrapper too.
  const bound = `<div class="modal"><div [attr.role]="'dialog'" aria-modal="true" aria-label="x" (keydown.escape)="c()"></div></div>`;
  assert.deepEqual(dialogFindings(bound), []);
});

test('an empty aria-label names nothing, aria-modal="false" is not modal, and a longer selector is another component', () => {
  const placeholders = `<div class="modal" role="dialog" aria-modal="false" aria-label="" (keydown.escape)="c()"></div>`;
  assert.deepEqual(rules(placeholders, placeholders), ['label', 'modal']);
  const sibling = `<div class="modal"><app-order-set-picker-row></app-order-set-picker-row></div>`;
  assert.deepEqual(rules(sibling, sibling, new Set(['app-order-set-picker'])), ['role']);
});

test('a native <dialog> has the role, the modality and Escape built in; only its name is checked', () => {
  const named = `<dialog class="modal" aria-labelledby="t" (keydown.escape)="close()"><h2 id="t">T</h2></dialog>`;
  assert.deepEqual(dialogFindings(named), []);
  const unnamed = `<dialog class="modal"><h2>T</h2></dialog>`;
  assert.deepEqual(rules(unnamed, unnamed), ['label']);
  const wrapped = `<div class="modal-wrapper"><dialog aria-label="x"></dialog></div>`;
  assert.deepEqual(dialogFindings(wrapped), []);
});

test('parts of a dialog (header, body, footer, backdrop, button) are not containers', () => {
  const html = `<div class="modal-backdrop"></div><div class="modal-header"></div>
    <div class="modal-body"></div><div class="modal-footer"></div><button class="modal-close"></button>
    <div class="dialog-overlay"></div>`;
  assert.deepEqual(dialogFindings(html), []);
});

test('round 16: an element with a shared dialog directive is a dialog; its Escape counts when the output is bound', () => {
  const bound = `<div class="modal" appDialogA11y aria-labelledby="t" (dialogClose)="close()"><h2 id="t">x</h2></div>
    <div class="x-dialog" appModalDialog aria-labelledby="u" (escaped)="close()"><h2 id="u">y</h2></div>`;
  assert.deepEqual(dialogFindings(bound), []);
  const unbound = `<div class="modal" appModalDialog aria-labelledby="t"><h2 id="t">x</h2></div>`;
  assert.deepEqual(
    dialogFindings(unbound).map((f) => f.rule),
    ['escape'],
  );
  const unnamed = `<div class="modal" appDialogA11y (dialogClose)="close()"></div>`;
  assert.deepEqual(
    dialogFindings(unnamed).map((f) => f.rule),
    ['label'],
  );
});

test('appDialogFocus closes on Escape when (dialogDismiss) is bound, but does not make its host a dialog', () => {
  const bound = `<div class="dialog-box" role="dialog" aria-modal="true" aria-labelledby="t" appDialogFocus (dialogDismiss)="close()"><h2 id="t">x</h2></div>`;
  assert.deepEqual(dialogFindings(bound), []);
  const unbound = `<div class="dialog-box" role="dialog" aria-modal="true" aria-labelledby="t" appDialogFocus><h2 id="t">x</h2></div>`;
  assert.deepEqual(
    dialogFindings(unbound).map((f) => f.rule),
    ['escape'],
  );
  // No role: the directive supplies none, so the container is still reported.
  const noRole = `<div class="dialog-box" aria-labelledby="t" appDialogFocus (dialogDismiss)="close()"><h2 id="t">x</h2></div>`;
  assert.deepEqual(
    dialogFindings(noRole).map((f) => f.rule),
    ['role'],
  );
});
