/** The element being printed; styles.scss keeps it, its ancestors and its content on paper. */
export const PRINT_ROOT_CLASS = 'print-root';
/** Set on <body> while one element is printed alone. */
export const PRINT_ISOLATED_CLASS = 'print-isolated';

/**
 * Prints one element of the page alone (polish 2026-11-13: the visit summary
 * open in a dialog over a list). While the print runs, every element that is
 * neither the root, one of its ancestors, nor inside it is hidden by the
 * global print rules in styles.scss; the marks are removed after the print.
 */
export function printOnly(root: HTMLElement, win: Window = window): void {
  const body = root.ownerDocument.body;
  const clear = (): void => {
    root.classList.remove(PRINT_ROOT_CLASS);
    body.classList.remove(PRINT_ISOLATED_CLASS);
    win.removeEventListener('afterprint', clear);
  };
  root.classList.add(PRINT_ROOT_CLASS);
  body.classList.add(PRINT_ISOLATED_CLASS);
  win.addEventListener('afterprint', clear);
  win.print();
}
