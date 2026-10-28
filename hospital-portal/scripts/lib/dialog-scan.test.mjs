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
    dialogFindings(keydown, `${keydown}\nif (event.key === 'Escape') this.close();`),
    [],
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
});

test('parts of a dialog (header, body, footer, backdrop, button) are not containers', () => {
  const html = `<div class="modal-backdrop"></div><div class="modal-header"></div>
    <div class="modal-body"></div><div class="modal-footer"></div><button class="modal-close"></button>
    <div class="dialog-overlay"></div>`;
  assert.deepEqual(dialogFindings(html), []);
});
