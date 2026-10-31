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
 * QA D947/D988 (WCAG 2.1.1, 2.4.3): the keyboard contract of a modal dialog.
 * Put it on the element that carries `role="dialog"` and `aria-modal="true"`:
 *
 * - focus moves into the dialog when it opens (its first focusable control,
 *   or the dialog itself);
 * - Escape emits `dialogDismiss`;
 * - Tab and Shift+Tab stay inside the dialog;
 * - focus returns to whatever had it before the dialog opened.
 *
 * Usage:
 *   <div role="dialog" aria-modal="true" appDialogFocus (dialogDismiss)="close()">…</div>
 */
@Directive({
  selector: '[appDialogFocus]',
  standalone: true,
})
export class DialogFocusDirective implements AfterViewInit, OnDestroy {
  @Output() readonly dialogDismiss = new EventEmitter<void>();

  private readonly host = inject(ElementRef<HTMLElement>);
  private previouslyFocused: HTMLElement | null = null;

  private static readonly FOCUSABLE = [
    'a[href]',
    'button:not([disabled])',
    'input:not([disabled]):not([type="hidden"])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
  ].join(',');

  ngAfterViewInit(): void {
    this.previouslyFocused = captureOpener();
    const el = this.host.nativeElement as HTMLElement;
    const first = this.focusables()[0];
    if (first) {
      first.focus();
    } else {
      if (!el.hasAttribute('tabindex')) {
        el.setAttribute('tabindex', '-1');
      }
      el.focus();
    }
  }

  ngOnDestroy(): void {
    restoreFocus(this.previouslyFocused);
  }

  @HostListener('keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.dialogDismiss.emit();
      return;
    }
    if (event.key !== 'Tab') {
      return;
    }
    const items = this.focusables();
    if (items.length === 0) {
      event.preventDefault();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const current = document.activeElement;
    if (event.shiftKey && (current === first || !this.host.nativeElement.contains(current))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && current === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private focusables(): HTMLElement[] {
    const el = this.host.nativeElement as HTMLElement;
    return Array.from(el.querySelectorAll<HTMLElement>(DialogFocusDirective.FOCUSABLE)).filter(
      (node) => !node.closest('[hidden]') && node.getAttribute('aria-hidden') !== 'true',
    );
  }
}
