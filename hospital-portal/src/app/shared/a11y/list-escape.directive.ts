import { Directive, EventEmitter, HostListener, Output, input } from '@angular/core';

/**
 * QA D1259 (D1251 sibling, round 26): Escape pressed in a field whose
 * suggestion list is open closed the whole dialog. The reception waitlist's
 * "Prestataire préféré": the combobox closed its list but let the key go on to
 * the dialog, which closed with the query (or asked "Abandonner le texte
 * saisi ?" over the reason already typed).
 *
 * Escape over an open list closes the list and nothing else:
 *
 * ```html
 * <input [appListEscape]="patientDropdownOpen()" (listEscape)="patientDropdownOpen.set(false)" />
 * ```
 *
 * While the list is closed the key goes on as before (the dialog closes, or
 * asks about typed text). `comboboxKey` (combobox-keys.ts) applies the same
 * rule to the ARIA comboboxes; `scripts/lib/list-escape.test.mjs` fails the
 * build for a suggestion list whose field lets Escape through.
 */
@Directive({
  selector: '[appListEscape]',
  standalone: true,
})
export class ListEscapeDirective {
  /** Whether the field's suggestion list is open now. */
  readonly appListEscape = input<boolean | null | undefined>(false);
  /** Escape was pressed over the open list: close it. */
  @Output() readonly listEscape = new EventEmitter<void>();

  @HostListener('keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape' && event.key !== 'Esc') return;
    if (!stopEscapeAtOpenList(event, !!this.appListEscape())) return;
    this.listEscape.emit();
  }
}

/**
 * Escape over an open list stops here: the dialog around the field (its
 * keydown listener, a document-level one, the typed-text guard) never sees it.
 * Returns whether the key was taken.
 */
export function stopEscapeAtOpenList(event: KeyboardEvent, listOpen: boolean): boolean {
  if (!listOpen) return false;
  event.preventDefault();
  event.stopPropagation();
  return true;
}
