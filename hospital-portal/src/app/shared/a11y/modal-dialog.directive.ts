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

/**
 * QA D1004: a hand-built overlay dialog behaves as a modal for keyboard and
 * screen-reader users. Put it on the dialog box (not the overlay):
 *
 *   <div class="x-dialog" appModalDialog aria-labelledby="x-title" (escaped)="close()">
 *
 * It sets `role="dialog"` and `aria-modal="true"`, moves focus into the box
 * when it opens, keeps Tab and Shift+Tab inside it, emits `escaped` on
 * Escape, and gives focus back to whatever had it when the dialog closes.
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

  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly opener: HTMLElement | null =
    typeof document === 'undefined' ? null : (document.activeElement as HTMLElement | null);

  private static readonly FOCUSABLE = [
    'a[href]',
    'button:not([disabled])',
    'input:not([disabled]):not([type="hidden"])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
  ].join(',');

  ngAfterViewInit(): void {
    const first = this.focusable()[0];
    (first ?? this.host.nativeElement).focus();
  }

  ngOnDestroy(): void {
    if (this.opener && typeof this.opener.focus === 'function' && this.opener.isConnected) {
      this.opener.focus();
    }
  }

  @HostListener('keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.escaped.emit();
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
    if (event.shiftKey && (active === first || active === this.host.nativeElement)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private focusable(): HTMLElement[] {
    const root = this.host.nativeElement as HTMLElement;
    return Array.from(root.querySelectorAll<HTMLElement>(ModalDialogDirective.FOCUSABLE)).filter(
      (el) => !el.hasAttribute('hidden') && el.getAttribute('aria-hidden') !== 'true',
    );
  }
}
