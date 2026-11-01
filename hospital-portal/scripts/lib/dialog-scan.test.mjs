#!/usr/bin/env node
/**
 * The dialog-semantics scan (QA D976, D988, D1004). Each case is a shape from
 * the round-16 report, plus the house shape that must pass.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { dialogFindings as scan } from './dialog-scan.mjs';

// The round-16 cases below test the role/modal/label/escape rules; the focus
// rule (QA D1030, round 17) has its own cases at the end, so it is read apart.
const dialogFindings = (...args) => scan(...args).filter((f) => f.rule !== 'focus');

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

test('a native <dialog> opened with showModal() has the role, the modality and Escape built in; only its name is checked', () => {
  const opens = 'this.ref.nativeElement.showModal();';
  const named = `<dialog class="modal" aria-labelledby="t" (keydown.escape)="close()"><h2 id="t">T</h2></dialog>`;
  assert.deepEqual(
    scan(
      named,
      `${named}
${opens}`,
    ),
    [],
  );
  const unnamed = `<dialog class="modal"><h2>T</h2></dialog>`;
  assert.deepEqual(
    rules(
      unnamed,
      `${unnamed}
${opens}`,
    ),
    ['label'],
  );
  const wrapped = `<div class="modal-wrapper"><dialog aria-label="x"></dialog></div>`;
  assert.deepEqual(
    scan(
      wrapped,
      `${wrapped}
${opens}`,
    ),
    [],
  );
});

test('QA D1030: a <dialog [open]> is not modal - checked like a hand-built dialog', () => {
  const open = `<dialog class="modal" [open]="show()" (keydown)="$event.stopPropagation()"><h2>T</h2></dialog>`;
  assert.deepEqual(
    scan(open, open)
      .map((f) => f.rule)
      .sort(),
    ['escape', 'focus', 'label', 'modal'],
  );
  const fixed = `<dialog class="modal" [open]="show()" appModalDialog aria-labelledby="t" (escaped)="close()"><h2 id="t">T</h2></dialog>`;
  assert.deepEqual(scan(fixed, fixed), []);
});

test('QA D1030: an inner (keydown.escape)="$event.stopPropagation()" swallows the backdrop close', () => {
  const swallowed = `<div class="modal-backdrop" (click)="close()" (keydown.escape)="close()">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="t" (keydown.escape)="$event.stopPropagation()"><h2 id="t">T</h2></div></div>`;
  assert.deepEqual(rules(swallowed, swallowed), ['escape']);
});

test('QA D1030: a hand-built dialog must manage focus (shared directive, CDK trap or dialog service)', () => {
  const bare = `<div class="modal" role="dialog" aria-modal="true" aria-label="x" (keydown.escape)="c()"></div>`;
  assert.deepEqual(
    scan(bare, bare).map((f) => f.rule),
    ['focus'],
  );
  const trapped = `<div class="modal" role="dialog" aria-modal="true" aria-label="x" cdkTrapFocus (keydown.escape)="c()"></div>`;
  assert.deepEqual(scan(trapped, trapped), []);
  const directive = `<div class="modal" appModalDialog aria-label="x" (escaped)="c()"></div>`;
  assert.deepEqual(scan(directive, directive), []);
  const service = `import { MatDialog } from '@angular/material/dialog';`;
  assert.deepEqual(
    scan(
      bare,
      `${bare}
${service}`,
    ),
    [],
  );
});

test("QA D1004 (round 19): a NESTED dialog's directive does not manage focus for the dialog around it", () => {
  // The outer form keeps focus on its opener; only the confirmation inside it is a modal.
  const outer = `<div class="modal" role="dialog" aria-modal="true" aria-labelledby="t" (keydown.escape)="c()">
    <h2 id="t">Form</h2>
    @if (confirm()) {
      <div class="modal" appModalDialog aria-labelledby="u" (escaped)="d()"><h3 id="u">Sure?</h3></div>
    }
  </div>`;
  assert.deepEqual(
    scan(outer, outer).map((f) => `${f.rule}:${f.line}`),
    ['focus:1'],
  );
});

test('QA D1004 (round 19): a (keydown.escape) on a control INSIDE a dialog does not close the dialog', () => {
  // A typeahead that clears its list on Escape is not the dialog's Escape handler.
  const typeahead = `<div class="modal" role="dialog" aria-modal="true" aria-labelledby="t" cdkTrapFocus>
    <h2 id="t">Search</h2><input (keydown.escape)="clearSuggestions()" />
  </div>`;
  assert.deepEqual(rules(typeahead, typeahead), ['escape']);
});

test('parts of a dialog (header, body, footer, backdrop, button) are not containers', () => {
  const html = `<div class="modal-backdrop"></div><div class="modal-header"></div>
    <div class="modal-body"></div><div class="modal-footer"></div><button class="modal-close"></button>
    <div class="dialog-overlay"></div>`;
  // Round 20: the lone backdrop of this fragment trips the new cover rule
  // (`overlay`, tested at the end); this test is about the container rule.
  assert.deepEqual(
    dialogFindings(html).filter((f) => f.rule !== 'overlay'),
    [],
  );
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

test('round 17 (D1004 class): an Escape that only stops propagation on the dialog swallows the backdrop’s', () => {
  const swallowed = `<div class="modal-backdrop" (click)="close()" (keydown.escape)="close()">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="t"
      (click)="$event.stopPropagation()" (keydown.escape)="$event.stopPropagation()">
      <h2 id="t">Title</h2>
    </div>
  </div>`;
  assert.deepEqual(rules(swallowed, swallowed), ['escape']);
  const closes = swallowed.replace(
    '(keydown.escape)="$event.stopPropagation()"',
    '(keydown.escape)="close(); $event.stopPropagation()"',
  );
  assert.deepEqual(rules(closes, closes), []);
});

test('QA D1125 (round 20): a sibling panel behind a backdrop that is not a dialog is a hand-built overlay', () => {
  // The shell bell panel and profile menu, the reception snapshot drawer.
  const bell = `<div class="overlay-backdrop" aria-hidden="true" (click)="close()"></div>
    <div class="notif-panel"><h3>Notifications</h3><button (click)="x()">x</button></div>`;
  assert.deepEqual(
    scan(bell, bell).map((f) => `${f.rule}:${f.anchor}`),
    ['overlay:overlay-backdrop + notif-panel'],
  );
  const fixed = bell.replace(
    '<div class="notif-panel">',
    '<div class="notif-panel" appModalDialog aria-labelledby="t" (escaped)="close()">',
  );
  assert.deepEqual(scan(fixed, fixed), []);
  // A dismissable *-overlay cover is read too; a loading veil (no click) is not.
  const drawer = `<div class="drawer-overlay" role="button" tabindex="0" (click)="c()"></div><div class="drawer-panel"><button>x</button></div>`;
  assert.deepEqual(
    scan(drawer, drawer).map((f) => f.rule),
    ['overlay'],
  );
  const veil = `<div class="loading-overlay"><div class="spinner"></div></div>`;
  assert.deepEqual(scan(veil, veil), []);
});

test('QA D1125 (round 20): (keydown.escape) on an element that can never hold focus is reported', () => {
  const hidden = `<div class="overlay-backdrop" aria-hidden="true" (click)="c()" (keydown.escape)="c()"></div>
    <div class="menu" appModalDialog aria-label="m" (escaped)="c()"><a href="/x">x</a></div>`;
  assert.deepEqual(
    scan(hidden, hidden).map((f) => f.rule),
    ['escape-target'],
  );
  // Focusable itself (tabindex), or wrapping something focusable: the key can reach it.
  const reachable = hidden.replace('aria-hidden="true"', 'tabindex="-1"');
  assert.deepEqual(scan(reachable, reachable), []);
  const wrapper = `<div class="card" (keydown.escape)="c()"><button>x</button></div>`;
  assert.deepEqual(scan(wrapper, wrapper), []);
});

test('QA D1125 (round 20): a navigation drawer closing on its own Escape is the one non-dialog panel', () => {
  const sidebar = `<aside class="sidebar" (keydown.escape)="closeSidebarFromKeyboard()"><nav><a href="/">x</a></nav></aside>
    <div class="sidebar-backdrop" aria-hidden="true" (click)="closeSidebar()"></div>
    <div class="main-area"><main><router-outlet /></main></div>`;
  assert.deepEqual(scan(sidebar, sidebar), []);
  const noEscape = sidebar.replace(' (keydown.escape)="closeSidebarFromKeyboard()"', '');
  assert.deepEqual(
    scan(noEscape, noEscape).map((f) => f.rule),
    ['overlay'],
  );
});

// QA D1155 / D1144 / D1131 (round 21): Escape in an inline action form inside a
// dialog bubbled to the dialog, which closed and dropped the typed reason.
const VIEW_DIALOG = (
  form,
) => `<div class="modal-backdrop" tabindex="-1" (click)="closeOrder()" (keydown.escape)="closeOrder()">
  <div class="modal" appModalDialog aria-labelledby="t" (escaped)="closeOrder()">
    <h2 id="t">Order</h2>
    @if (showCancelPrompt()) {
      ${form}
    }
    <button (click)="closeOrder()">Close</button>
  </div>
</div>`;

const subform = (html, source = html) =>
  scan(html, source)
    .filter((f) => f.rule === 'subform')
    .map((f) => f.anchor);

test('QA D1155: an inline cancel form inside a dialog without appInlineSubForm is reported', () => {
  const html = VIEW_DIALOG(`<div class="action-form">
        <h3>Cancel the order</h3>
        <textarea id="r" [(ngModel)]="reason"></textarea>
        <button (click)="closeCancelPrompt()">Cancel</button>
        <button (click)="submitCancel()">Confirm</button>
      </div>`);
  assert.deepEqual(subform(html), ['action-form <Cancel the order>']);
});

test('QA D1155: the same form with appInlineSubForm and (dismissed) passes; without (dismissed) it does not', () => {
  const form = `<div class="action-form" appInlineSubForm #f="inlineSubForm" (dismissed)="closeCancelPrompt()">
        <textarea id="r"></textarea>
        <button (click)="closeCancelPrompt()">Cancel</button>
        <app-inline-discard-ask [form]="f" />
      </div>`;
  assert.deepEqual(subform(VIEW_DIALOG(form)), []);
  assert.equal(
    subform(VIEW_DIALOG(form.replace(' (dismissed)="closeCancelPrompt()"', ''))).length,
    1,
  );
});

test('QA D1131: a form whose own (keydown.escape) handler stops the key passes (the encounter addendum)', () => {
  const form = `<div class="addendum-form" (keydown.escape)="onAddendumEscape($event)">
        <textarea></textarea><button (click)="closeAddendumForm(true)">Cancel</button>
      </div>`;
  const html = VIEW_DIALOG(form);
  const stops = `${html}\nonAddendumEscape(event: Event): void { event.stopPropagation(); this.close(); }`;
  assert.deepEqual(subform(html, stops), []);
  const bubbles = `${html}\nonAddendumEscape(event: Event): void { this.close(); }`;
  assert.equal(subform(html, bubbles).length, 1);
});

test('QA D1155: a flag switched off by the button counts as the form closing itself; the dialog close does not', () => {
  const flag = VIEW_DIALOG(
    `<div class="consent"><input id="by" /><button (click)="consent.set(false)">Cancel</button></div>`,
  );
  assert.equal(subform(flag).length, 1);
  // A conditional section whose only button closes the whole dialog is the dialog's own body.
  const body = VIEW_DIALOG(
    `<div class="extra"><input id="x" /><button (click)="closeOrder()">Close</button></div>`,
  );
  assert.deepEqual(subform(body), []);
  // No text field: nothing typed to lose.
  const noField = VIEW_DIALOG(
    `<div class="x"><input type="checkbox" /><button (click)="hideX()">Hide</button></div>`,
  );
  assert.deepEqual(subform(noField), []);
});

test('QA D1155: a field and a Close in exclusive @if/@else branches are a panel, not a form; separate @if cells are one form', () => {
  const panel = VIEW_DIALOG(`<div class="cds">
        @if (blocked()) {
          <textarea id="override"></textarea>
        } @else {
          <button (click)="dismissAdvisories()">Close</button>
        }
      </div>`);
  assert.deepEqual(subform(panel), []);
  const row = VIEW_DIALOG(`<table class="lines">
        <tr>
          @if (editing()) { <td><input id="qty" /></td> }
          @if (editing()) { <td><button (click)="cancelEditLine()">Cancel</button></td> }
        </tr>
      </table>`);
  assert.deepEqual(subform(row), ['lines']);
});

test('QA D1155: an inline form outside any dialog is not this rule', () => {
  const html = `@if (open()) { <div class="f"><textarea></textarea><button (click)="cancel()">Cancel</button></div> }`;
  assert.deepEqual(subform(html), []);
});
