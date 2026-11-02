/**
 * QA D1125 / D1004 (round 20): where focus goes back when an overlay closes.
 *
 * The dialog directives remembered `document.activeElement` when the dialog
 * opened. That is the opener only when the opener still holds focus. The
 * nursing-intake button disables itself while the visit is looked up
 * (`[disabled]="encounterLookupInProgress()"`), and a disabled button loses
 * focus to the page body: by the time the dialog opened, the "opener" was
 * BODY, and focus came back to BODY on close.
 *
 * So the page keeps the last element that RECEIVED focus. A disabled or
 * removed element moves focus to the body without a `focusin`, so the last
 * focused element survives the lookup and is the opener.
 */
let lastFocused: HTMLElement | null = null;
let tracking = false;

function track(): void {
  if (tracking || typeof document === 'undefined') return;
  tracking = true;
  document.addEventListener(
    'focusin',
    (event) => {
      if (event.target instanceof HTMLElement && event.target !== document.body) {
        lastFocused = event.target;
      }
    },
    true,
  );
}

// Installed when the module loads, so the opener of the first dialog is known.
track();

/**
 * The element an overlay opening now should give focus back to: the focused
 * element, or, when focus has fallen to the page, the last element that held it.
 */
export function captureOpener(): HTMLElement | null {
  if (typeof document === 'undefined') return null;
  const active = document.activeElement;
  if (active instanceof HTMLElement && active !== document.body) return active;
  return lastFocused?.isConnected ? lastFocused : null;
}

/** Give focus back to `opener` if it is still on the page. */
export function restoreFocus(opener: HTMLElement | null): void {
  if (opener?.isConnected && typeof opener.focus === 'function') opener.focus();
}
