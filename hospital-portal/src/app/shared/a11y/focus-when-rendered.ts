/**
 * QA D1131 / D1144 (WCAG 2.4.3): an inline form that replaces the button that
 * opened it (an addendum, a cancel prompt, a referral action) takes the
 * keyboard with it. Removing the focused button dropped focus to the page
 * body, so the next Tab started from the top of the page and Escape reached
 * whatever listened at page level.
 *
 * Focuses the first element of `root` matching `selector`, once the current
 * change-detection pass has rendered it (the form appears from the same
 * click). Returns nothing: a selector that never matches is a no-op.
 */
export function focusWhenRendered(root: ParentNode | null | undefined, selector: string): void {
  if (!root) return;
  setTimeout(() => {
    root.querySelector<HTMLElement>(selector)?.focus();
  }, 0);
}
