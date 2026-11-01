import {
  AfterViewInit,
  Directive,
  ElementRef,
  EventEmitter,
  HostListener,
  OnDestroy,
  Output,
  inject,
  input,
  signal,
} from '@angular/core';

import { captureOpener, restoreFocus } from './focus-return';
import { focusWhenRendered } from './focus-when-rendered';

/** The fields whose typed text an Escape must not throw away unasked. */
const TEXT_FIELDS = [
  'textarea',
  'input:not([type])',
  'input[type="text"]',
  'input[type="search"]',
  'input[type="email"]',
  'input[type="tel"]',
  'input[type="url"]',
  'input[type="number"]',
  'input[type="date"]',
  'input[type="time"]',
  'input[type="datetime-local"]',
].join(',');

/** The first control the keyboard lands on when the form opens. */
const FIRST_CONTROL = [
  'textarea:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
].join(',');

/**
 * QA D1155 / D1144 / D1131 (NOT_FIXED in rounds 19–21): an inline action form
 * INSIDE a dialog (an ultrasound cancel prompt, a referral cancel or
 * acknowledgement, a procedure's schedule/consent/cancel step, an addendum).
 *
 * Escape pressed in such a form bubbled up to the dialog, which closed and
 * took the typed reason with it. Put this on the form's root element:
 *
 * ```html
 * <div class="action-form" appInlineSubForm #sub="inlineSubForm" (dismissed)="closePrompt()">
 *   …fields…
 *   <app-inline-discard-ask [form]="sub" />
 * </div>
 * ```
 *
 * - The keyboard moves into the form's first field when it opens.
 * - Escape inside it belongs to it: the key never reaches the dialog, its
 *   backdrop or a page-level listener.
 * - A form whose fields are as they were when it opened closes (`dismissed`,
 *   bound to the same method as the form's own Cancel button).
 * - A form holding typed text asks first (`asking()`, rendered by
 *   `<app-inline-discard-ask>`); Escape on that question goes back to the text.
 * - When the form goes away and nothing else took the keyboard (it would
 *   otherwise fall to the page body), focus goes back to the control that
 *   opened it — or to the element `inlineSubFormReturn` names inside the
 *   dialog, when given (an opener hidden while the form is open, as an
 *   `@if (!action())` button, is a new element afterwards); failing both, to
 *   the dialog itself — never to the page body.
 *
 * `npm run guard:a11y-dialogs` (rule `subform`, scripts/lib/dialog-scan.mjs)
 * fails the build for an inline form inside a dialog without it.
 */
@Directive({
  selector: '[appInlineSubForm]',
  standalone: true,
  exportAs: 'inlineSubForm',
})
export class InlineSubFormDirective implements AfterViewInit, OnDestroy {
  /** The form is to close: Escape on an empty form, or "Discard" on the question. */
  @Output() readonly dismissed = new EventEmitter<void>();

  /** Escape was pressed over typed text: the form asks before discarding it. */
  readonly asking = signal(false);

  /**
   * A selector, looked up in the dialog around the form, for where the
   * keyboard goes when the form closes and its opener is no longer on the page.
   */
  readonly inlineSubFormReturn = input<string | null>(null);

  private readonly host = inject(ElementRef<HTMLElement>);
  /** The control that opened the form (the button the click focused). */
  private readonly opener: HTMLElement | null = captureOpener();
  /** Each field's value once the form had rendered: a prefilled value is not "typed". */
  private readonly initial = new WeakMap<Element, string>();
  /** The dialog the form sits in, found while the form is still attached to it. */
  private dialog: HTMLElement | null = null;

  ngAfterViewInit(): void {
    const box = this.box();
    this.dialog =
      box.parentElement?.closest<HTMLElement>('[role="dialog"], [aria-modal="true"]') ?? null;
    // ngModel writes its value a tick later: read the starting values after it.
    setTimeout(() => {
      for (const field of this.textFields()) this.initial.set(field, field.value ?? '');
    }, 0);
    if (box.contains(document.activeElement)) return;
    focusWhenRendered(box, FIRST_CONTROL);
  }

  ngOnDestroy(): void {
    // Whoever closed the form may move the keyboard on purpose (a success
    // handler focusing the dialog's Close); only a keyboard left on the page
    // body goes back to the opener.
    const opener = this.opener;
    const dialog = this.dialog;
    const returnTo = this.inlineSubFormReturn();
    setTimeout(() => {
      const active = document.activeElement;
      if (active !== null && active !== document.body) return;
      const named =
        returnTo && dialog?.isConnected ? dialog.querySelector<HTMLElement>(returnTo) : null;
      if (named) {
        named.focus();
      } else if (opener?.isConnected) {
        restoreFocus(opener);
      } else if (dialog?.isConnected) {
        if (!dialog.hasAttribute('tabindex')) dialog.setAttribute('tabindex', '-1');
        dialog.focus();
      }
    }, 0);
  }

  @HostListener('keydown.escape', ['$event'])
  onEscape(event: Event): void {
    event.preventDefault();
    // The form's own key: the dialog around it must not close as well.
    event.stopPropagation();
    if (this.asking()) {
      this.keep();
    } else if (this.hasTypedText()) {
      this.asking.set(true);
      focusWhenRendered(this.box(), '[data-inline-keep]');
    } else {
      this.dismissed.emit();
    }
  }

  /** Back to the typed text, intact. */
  keep(): void {
    this.asking.set(false);
    focusWhenRendered(this.box(), FIRST_CONTROL);
  }

  /** The reader chose to throw the typed text away: the form closes. */
  discard(): void {
    this.asking.set(false);
    this.dismissed.emit();
  }

  /** Whether any text field of the form holds something typed since it opened. */
  hasTypedText(): boolean {
    return this.textFields().some((field) => {
      const value = (field.value ?? '').trim();
      return value !== '' && value !== (this.initial.get(field) ?? '').trim();
    });
  }

  private textFields(): HTMLInputElement[] {
    return Array.from(this.box().querySelectorAll<HTMLInputElement>(TEXT_FIELDS));
  }

  private box(): HTMLElement {
    return this.host.nativeElement as HTMLElement;
  }
}
