import { TranslateService } from '@ngx-translate/core';

/**
 * QA D1131 / D1144 / D1190 / D1195 / D1208 (NOT_FIXED rounds 19–22): Escape
 * pressed in a dialog threw away what the reader had typed.
 *
 * Each round fixed the dialogs it named and the next round found the class in
 * another one: the precaution form of the bed board, the invoice's add-line
 * and adjustment forms, the specimen reject reason. So the rule now lives in
 * the dialog directives themselves (appModalDialog, appDialogFocus,
 * appDialogA11y, appDrawerDialog): every dialog of the portal gets it.
 *
 * - A text field the reader typed into (an `input` event, so a value the
 *   form put there is not "typed") and that still holds something different
 *   from what it held when it took focus makes the dialog "hold typed text".
 * - Escape over typed text does not close: the key stops here (no handler of
 *   the dialog, its backdrop or the page sees it) and the dialog asks
 *   "Discard what you typed?" with Keep (back to the field, text intact) and
 *   Discard (the Escape goes on, and the dialog closes as it always did).
 * - Escape while asking keeps the text.
 * - A dialog with nothing typed closes on Escape exactly as before.
 *
 * Fields that hold a query, not work (a search box, a picker's typeahead), are
 * marked `data-escape-discards` (or `type="search"`, `role="combobox"`) and do
 * not count. An inline sub-form inside the dialog (appInlineSubForm) keeps its
 * own Escape and never reaches this.
 */

/** The fields whose typed text Escape must not throw away unasked. */
const TEXT_FIELD = [
  'textarea',
  'input:not([type])',
  'input[type="text"]',
  'input[type="email"]',
  'input[type="tel"]',
  'input[type="url"]',
  'input[type="number"]',
].join(',');

/** A field that holds a query, not work. */
const QUERY_FIELD = '[data-escape-discards], input[type="search"], [role="combobox"]';

/** Marks the Escape the guard sends on after "Discard", so it is not asked about again. */
const PASS = Symbol('escape-keeps-typed-text:pass');

type PassingEvent = KeyboardEvent & { [PASS]?: true };

function isTextField(el: EventTarget | null): el is HTMLInputElement | HTMLTextAreaElement {
  return el instanceof HTMLElement && el.matches(TEXT_FIELD) && !el.matches(QUERY_FIELD);
}

export class EscapeKeepsTypedText {
  /** Each field's value when it first took focus (before anything was typed). */
  private readonly before = new WeakMap<Element, string>();
  /** The fields the reader typed into. */
  private readonly typed = new Set<HTMLInputElement | HTMLTextAreaElement>();
  private ask: HTMLElement | null = null;
  /** The field focus was on when the question was asked. */
  private askedFrom: HTMLElement | null = null;

  private readonly onFocusIn = (event: Event) => {
    const el = event.target;
    if (isTextField(el) && !this.before.has(el)) this.before.set(el, el.value ?? '');
  };

  private readonly onInput = (event: Event) => {
    const el = event.target;
    if (isTextField(el)) this.typed.add(el);
  };

  private readonly onKeydown = (event: KeyboardEvent) => {
    if (event.key === 'Escape' || event.key === 'Esc') this.intercept(event);
  };

  /**
   * @param box the dialog element
   * @param translate for the question's words (the keys themselves without it)
   */
  constructor(
    private readonly box: HTMLElement,
    private readonly translate: TranslateService | null,
  ) {
    // Registered from the directive's constructor, so before any other
    // keydown listener on the box (the template's `(keydown.escape)`, the
    // directive's own HostListener): stopping the key here stops all of them.
    box.addEventListener('focusin', this.onFocusIn, true);
    box.addEventListener('input', this.onInput, true);
    box.addEventListener('keydown', this.onKeydown);
  }

  destroy(): void {
    this.box.removeEventListener('focusin', this.onFocusIn, true);
    this.box.removeEventListener('input', this.onInput, true);
    this.box.removeEventListener('keydown', this.onKeydown);
    this.ask = null;
  }

  /** Whether the question is on screen. */
  asking(): boolean {
    return this.ask?.isConnected === true;
  }

  /** Whether a field of the dialog holds text the reader typed and would lose. */
  hasTypedText(): boolean {
    for (const el of this.typed) {
      if (!el.isConnected || !this.box.contains(el)) continue;
      const value = (el.value ?? '').trim();
      if (value !== '' && value !== (this.before.get(el) ?? '').trim()) return true;
    }
    return false;
  }

  /**
   * Called for an Escape the dialog is about to act on (also from a
   * directive's document-level listener, when focus is outside the box).
   * Returns true when the guard took the key: the caller must not close.
   */
  intercept(event: KeyboardEvent): boolean {
    if ((event as PassingEvent)[PASS]) return false;
    if (this.asking()) {
      this.consume(event);
      this.keep();
      return true;
    }
    if (!this.hasTypedText()) return false;
    this.consume(event);
    this.showQuestion();
    return true;
  }

  /** Back to the typed text, intact. */
  keep(): void {
    this.removeQuestion();
    const back = this.askedFrom?.isConnected ? this.askedFrom : this.firstTyped();
    back?.focus();
  }

  /** The reader chose to throw the text away: the Escape goes on and the dialog closes as before. */
  discard(): void {
    const from =
      this.askedFrom?.isConnected && this.box.contains(this.askedFrom) ? this.askedFrom : this.box;
    this.removeQuestion();
    this.typed.clear();
    const escape = new KeyboardEvent('keydown', {
      key: 'Escape',
      code: 'Escape',
      bubbles: true,
      cancelable: true,
    }) as PassingEvent;
    escape[PASS] = true;
    from.dispatchEvent(escape);
  }

  private consume(event: KeyboardEvent): void {
    event.preventDefault();
    event.stopImmediatePropagation();
  }

  private firstTyped(): HTMLElement | null {
    for (const el of this.typed) if (el.isConnected && this.box.contains(el)) return el;
    return null;
  }

  private words(key: string): string {
    return this.translate?.instant(key) ?? key;
  }

  private showQuestion(): void {
    const active = document.activeElement;
    this.askedFrom =
      active instanceof HTMLElement && this.box.contains(active) ? active : this.firstTyped();
    const doc = this.box.ownerDocument;
    const ask = doc.createElement('div');
    ask.className = 'inline-discard dialog-discard';
    ask.setAttribute('role', 'alert');
    ask.setAttribute('data-testid', 'dialog-discard-ask');
    const text = doc.createElement('p');
    text.textContent = this.words('INLINE_FORM.DISCARD_ASK');
    const keep = doc.createElement('button');
    keep.type = 'button';
    keep.className = 'btn-secondary';
    keep.setAttribute('data-testid', 'dialog-keep');
    keep.textContent = this.words('INLINE_FORM.KEEP');
    keep.addEventListener('click', () => this.keep());
    const discard = doc.createElement('button');
    discard.type = 'button';
    discard.className = 'btn-danger';
    discard.setAttribute('data-testid', 'dialog-discard');
    discard.textContent = this.words('INLINE_FORM.DISCARD');
    discard.addEventListener('click', () => this.discard());
    ask.append(text, keep, discard);
    this.box.appendChild(ask);
    this.ask = ask;
    keep.focus();
  }

  private removeQuestion(): void {
    this.ask?.remove();
    this.ask = null;
  }
}
