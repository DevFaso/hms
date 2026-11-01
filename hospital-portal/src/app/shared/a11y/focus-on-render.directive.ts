import { AfterViewInit, Directive, ElementRef, OnDestroy, inject, input } from '@angular/core';

/**
 * QA D1175 / D1144 (round 21): an inline editor that REPLACES the control
 * that opened it (a price shown as text with a pencil, the pencil swapped
 * for an input; a role badge swapped for a select). Removing the focused
 * pencil dropped the keyboard to the page body, and the next Tab skipped the
 * new input.
 *
 * Put it on the editor's field:
 *
 * ```html
 * @if (editingPriceId() === item.id) {
 *   <input appFocusOnRender [focusOnRenderReturn]="'[data-testid=price-edit-' + item.id + ']'" … />
 * } @else {
 *   <button [attr.data-testid]="'price-edit-' + item.id" (click)="editingPriceId.set(item.id)">…</button>
 * }
 * ```
 *
 * The field takes the keyboard when it appears. When it goes away (saved,
 * Escape, Cancel) and nothing else took the keyboard, focus goes to the
 * element `focusOnRenderReturn` names in the same row — the pencil, rendered
 * again — never to the page body.
 *
 * `scripts/lib/inline-editor-focus.test.mjs` fails the build for a row
 * editor (`@if (editingX() === row.id) { <field> }`) without it.
 */
@Directive({
  selector: '[appFocusOnRender]',
  standalone: true,
})
export class FocusOnRenderDirective implements AfterViewInit, OnDestroy {
  /** A selector, looked up in the editor's row, for where the keyboard goes when it closes. */
  readonly focusOnRenderReturn = input<string | null>(null);

  private readonly host = inject(ElementRef<HTMLElement>);
  /** The row (or form) the editor sits in, found while the editor is attached to it. */
  private scope: HTMLElement | null = null;

  ngAfterViewInit(): void {
    const el = this.host.nativeElement as HTMLElement;
    this.scope = el.closest<HTMLElement>('tr, li, [role="row"], form, section, fieldset');
    setTimeout(() => {
      if (el.isConnected) el.focus();
    }, 0);
  }

  ngOnDestroy(): void {
    const scope = this.scope;
    const selector = this.focusOnRenderReturn();
    if (!scope || !selector) return;
    setTimeout(() => {
      const active = document.activeElement;
      if (active !== null && active !== document.body) return;
      if (scope.isConnected) scope.querySelector<HTMLElement>(selector)?.focus();
    }, 0);
  }
}
