import {
  AfterViewInit,
  Directive,
  ElementRef,
  EventEmitter,
  HostListener,
  OnDestroy,
  Output,
  inject,
} from '@angular/core';

import { captureOpener, restoreFocus } from './focus-return';

/**
 * QA D1004: a hand-built overlay dialog behaves as a modal for keyboard and
 * screen-reader users. Put it on the dialog box (not the overlay):
 *
 *   <div class="x-dialog" appModalDialog aria-labelledby="x-title" (escaped)="close()">
 *
 * It sets `role="dialog"` and `aria-modal="true"`, moves focus into the box
 * when it opens (its first focusable control, else the box itself), keeps
 * Tab and Shift+Tab inside it, emits `escaped` on Escape, and gives focus
 * back to whatever had it when the dialog closes.
 *
 * Bind `(escaped)` to the SAME method the dialog's Cancel button calls, so a
 * busy or unsaved-changes guard applies to Escape too. A box that already
 * closes itself from its own `(keydown.escape)` does not also bind `(escaped)`
 * (the close would run twice).
 *
 * With dialogs stacked (a confirmation opened over a form), only the top one
 * answers the keyboard, and an Escape handled through `(escaped)` stops there:
 * it never also closes the dialog underneath or reaches a page-level Escape
 * listener.
 */
@Directive({
  selector: '[appModalDialog]',
  standalone: true,
  host: {
    role: 'dialog',
    'aria-modal': 'true',
  },
})
export class ModalDialogDirective implements AfterViewInit, OnDestroy {
  @Output() readonly escaped = new EventEmitter<void>();

  /** Open dialogs, oldest first; the last one is on top. */
  private static readonly stack: ModalDialogDirective[] = [];

  private readonly host = inject(ElementRef<HTMLElement>);
  /** QA D1125: the opener even when it had disabled itself (focus-return.ts). */
  private readonly opener: HTMLElement | null = captureOpener();

  private static readonly FOCUSABLE = [
    'a[href]',
    'button:not([disabled])',
    'input:not([disabled]):not([type="hidden"])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
  ].join(',');

  constructor() {
    ModalDialogDirective.stack.push(this);
  }

  ngAfterViewInit(): void {
    const box = this.box();
    if (box.contains(document.activeElement) && document.activeElement !== box) return;
    const first = this.focusable()[0];
    if (first) {
      first.focus();
      return;
    }
    // A box with nothing focusable (a read-only view) still takes focus, so
    // Escape reaches it and a screen reader announces it.
    if (!box.hasAttribute('tabindex')) box.setAttribute('tabindex', '-1');
    box.focus();
  }

  ngOnDestroy(): void {
    const stack = ModalDialogDirective.stack;
    const at = stack.indexOf(this);
    if (at >= 0) stack.splice(at, 1);
    restoreFocus(this.opener);
  }

  /**
   * Focus that left the box (a click on the backdrop moves it to the body):
   * Escape still closes the top dialog and Tab comes back in.
   */
  @HostListener('document:keydown', ['$event'])
  onDocumentKeydown(event: KeyboardEvent): void {
    if (!this.isTop() || this.box().contains(document.activeElement)) return;
    // Focus is inside another modal built without this directive (a CDK
    // dialog, appDialogFocus): that one owns the keyboard, never pull focus out.
    const active = document.activeElement;
    if (active instanceof HTMLElement && active.closest('[aria-modal="true"]')) return;
    if (event.key === 'Escape' && this.escaped.observed) {
      event.preventDefault();
      this.escaped.emit();
    } else if (event.key === 'Tab') {
      event.preventDefault();
      (this.focusable()[0] ?? this.box()).focus();
    }
  }

  @HostListener('keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (!this.isTop()) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      if (this.escaped.observed) {
        // Handled here: the dialog underneath and a page-level Escape
        // listener must not close as well.
        event.stopPropagation();
        this.escaped.emit();
      }
      return;
    }
    if (event.key !== 'Tab') return;
    const items = this.focusable();
    if (items.length === 0) {
      event.preventDefault();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || active === this.box())) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private isTop(): boolean {
    const stack = ModalDialogDirective.stack;
    return stack[stack.length - 1] === this;
  }

  private box(): HTMLElement {
    return this.host.nativeElement as HTMLElement;
  }

  private focusable(): HTMLElement[] {
    return Array.from(
      this.box().querySelectorAll<HTMLElement>(ModalDialogDirective.FOCUSABLE),
    ).filter((el) => !el.closest('[hidden]') && el.getAttribute('aria-hidden') !== 'true');
  }
}
