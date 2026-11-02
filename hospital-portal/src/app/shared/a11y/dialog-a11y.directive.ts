import {
  AfterViewInit,
  Directive,
  ElementRef,
  HostListener,
  OnDestroy,
  OnInit,
  inject,
  output,
} from '@angular/core';

import { TranslateService } from '@ngx-translate/core';

import { EscapeKeepsTypedText } from './escape-keeps-typed-text';
import { captureOpener, restoreFocus } from './focus-return';
import { OverlayLayer } from './overlay-stack';

/**
 * QA D976: the billing and patient-portal modals left focus on the row
 * button behind them, never closed on Escape, let Tab walk into the page
 * underneath, and several had no dialog role.
 *
 * Put on the dialog box itself (not the backdrop):
 *
 *     <div class="modal" appDialogA11y aria-labelledby="pay-title" (dialogClose)="close()">
 *
 * It marks the box `role="dialog"` + `aria-modal="true"`, moves focus into
 * it when it opens (the first `[autofocus]` element, else the first focusable
 * one, else the box), keeps Tab and Shift+Tab inside it, closes it on Escape,
 * and gives focus back to what had it before. With dialogs stacked, only the
 * top one answers the keyboard.
 */
@Directive({
  selector: '[appDialogA11y]',
  standalone: true,
  host: {
    role: 'dialog',
    'aria-modal': 'true',
    tabindex: '-1',
  },
})
export class DialogA11yDirective implements OnInit, AfterViewInit, OnDestroy {
  /** Escape was pressed while this was the top dialog. */
  readonly dialogClose = output<void>();

  private static readonly FOCUSABLE = [
    'a[href]',
    'button:not([disabled])',
    'input:not([disabled]):not([type="hidden"])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
  ].join(',');

  private readonly host = inject(ElementRef<HTMLElement>);
  private opener: HTMLElement | null = null;
  /** QA D1209: one stack for every dialog directive (z-order and keyboard). */
  private layer: OverlayLayer | null = null;
  /** QA D1131 class (round 22): Escape never throws typed text away unasked. */
  private readonly typedText = new EscapeKeepsTypedText(
    this.host.nativeElement as HTMLElement,
    inject(TranslateService, { optional: true }),
  );

  ngOnInit(): void {
    this.opener = captureOpener();
    this.layer = new OverlayLayer(this.host.nativeElement as HTMLElement);
    // After the content inside the box has rendered.
    setTimeout(() => this.focusInitial());
  }

  ngAfterViewInit(): void {
    this.layer?.raise();
  }

  ngOnDestroy(): void {
    this.layer?.close();
    this.typedText.destroy();
    restoreFocus(this.opener);
  }

  private isTop(): boolean {
    return this.layer?.isTop() ?? false;
  }

  private focusables(): HTMLElement[] {
    const box: HTMLElement = this.host.nativeElement;
    return Array.from(box.querySelectorAll<HTMLElement>(DialogA11yDirective.FOCUSABLE)).filter(
      (el) => !el.closest('[hidden]') && el.getAttribute('aria-hidden') !== 'true',
    );
  }

  private focusInitial(): void {
    if (!this.isTop()) return;
    const box: HTMLElement = this.host.nativeElement;
    if (box.contains(document.activeElement) && document.activeElement !== box) return;
    const first =
      box.querySelector<HTMLElement>('[autofocus]:not([disabled])') ?? this.focusables()[0] ?? box;
    first.focus();
  }

  /** Focus that left the box (to the page body): Escape still closes the top dialog, Tab comes back in. */
  @HostListener('document:keydown', ['$event'])
  onDocumentKeydown(event: KeyboardEvent): void {
    const box: HTMLElement = this.host.nativeElement;
    if (!this.isTop() || box.contains(document.activeElement)) return;
    this.onKeydown(event);
    if (event.key === 'Tab' && !event.defaultPrevented) {
      event.preventDefault();
      (this.focusables()[0] ?? box).focus();
    }
  }

  /**
   * On the box itself: focus is moved in when it opens and kept inside, and
   * the templates' own `(keydown.escape)="$event.stopPropagation()"` (there
   * for the backdrop's click/key lint pair) cannot swallow a listener on the
   * same element.
   */
  @HostListener('keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (!this.isTop()) return;
    if (event.key === 'Escape' && this.typedText.intercept(event)) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.dialogClose.emit();
      return;
    }
    if (event.key !== 'Tab') return;
    const box: HTMLElement = this.host.nativeElement;
    const items = this.focusables();
    if (items.length === 0) {
      event.preventDefault();
      box.focus();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || active === box)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
