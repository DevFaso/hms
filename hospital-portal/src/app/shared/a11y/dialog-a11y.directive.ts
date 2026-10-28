import {
  Directive,
  ElementRef,
  HostListener,
  OnDestroy,
  OnInit,
  inject,
  output,
} from '@angular/core';

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
export class DialogA11yDirective implements OnInit, OnDestroy {
  /** Escape was pressed while this was the top dialog. */
  readonly dialogClose = output<void>();

  private static readonly stack: DialogA11yDirective[] = [];
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

  ngOnInit(): void {
    const active = document.activeElement;
    this.opener = active instanceof HTMLElement ? active : null;
    DialogA11yDirective.stack.push(this);
    // After the content inside the box has rendered.
    setTimeout(() => this.focusInitial());
  }

  ngOnDestroy(): void {
    const stack = DialogA11yDirective.stack;
    const at = stack.indexOf(this);
    if (at >= 0) stack.splice(at, 1);
    const opener = this.opener;
    if (opener?.isConnected) opener.focus();
  }

  private isTop(): boolean {
    const stack = DialogA11yDirective.stack;
    return stack[stack.length - 1] === this;
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

  /**
   * On the box itself: focus is moved in when it opens and kept inside, and
   * the templates' own `(keydown.escape)="$event.stopPropagation()"` (there
   * for the backdrop's click/key lint pair) cannot swallow a listener on the
   * same element.
   */
  @HostListener('keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (!this.isTop()) return;
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
