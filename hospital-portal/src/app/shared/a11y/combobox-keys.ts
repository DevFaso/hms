/**
 * QA D947 (WCAG 2.1.1): the keyboard of a typeahead picker, ARIA combobox
 * pattern. The input keeps focus; ArrowDown/ArrowUp move the active option,
 * Enter picks it, Escape closes the list. Use with `role="combobox"`,
 * `aria-expanded`, `aria-controls` and `aria-activedescendant` on the input,
 * and `role="listbox"` / `role="option"` on the list.
 *
 * Returns what the picker must do; the caller owns its state.
 */
export interface ComboboxStep {
  /** The option now active (-1: none). */
  active: number;
  /** Pick the option at `active`. */
  pick: boolean;
  /** Close the list. */
  close: boolean;
}

export function comboboxKey(
  event: KeyboardEvent,
  optionCount: number,
  active: number,
): ComboboxStep {
  const stay: ComboboxStep = { active, pick: false, close: false };
  if (optionCount <= 0) {
    return event.key === 'Escape' ? { active: -1, pick: false, close: true } : stay;
  }
  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault();
      return { active: active < optionCount - 1 ? active + 1 : 0, pick: false, close: false };
    case 'ArrowUp':
      event.preventDefault();
      return { active: active > 0 ? active - 1 : optionCount - 1, pick: false, close: false };
    case 'Home':
      event.preventDefault();
      return { active: 0, pick: false, close: false };
    case 'End':
      event.preventDefault();
      return { active: optionCount - 1, pick: false, close: false };
    case 'Enter':
      if (active >= 0 && active < optionCount) {
        event.preventDefault();
        return { active, pick: true, close: true };
      }
      return stay;
    case 'Escape':
      event.preventDefault();
      return { active: -1, pick: false, close: true };
    case 'Tab':
      // Tab picks the active option (a keyboard user who arrowed to it meant
      // it), then lets focus move on as usual.
      return active >= 0 && active < optionCount
        ? { active, pick: true, close: true }
        : { active: -1, pick: false, close: true };
    default:
      return stay;
  }
}
