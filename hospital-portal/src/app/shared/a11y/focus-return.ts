import { awaitReturn, focusKeyOf, type FocusKey } from './focus-rescue';

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
 * QA D1260 (D1004 sibling, round 26): a dialog opened from INSIDE another one
 * that closes at the same moment ("Nom d'utilisateur oublié ?" → "Réinitialiser
 * le mot de passe" on the login page). The second dialog's opener is a button
 * of the first, removed with it, so focus fell to the page root when the
 * second one closed. Each dialog registers its opener here; an opener that
 * sits inside a registered dialog remembers that dialog's own opener, and
 * {@link restoreFocus} walks back along the chain to the first control still
 * on the page — the link that opened the first dialog.
 */
const openerOfDialog = new WeakMap<HTMLElement, HTMLElement | null>();
const fallbackOf = new WeakMap<HTMLElement, HTMLElement>();
/** QA D1283: each opener's key, taken while it was on the page, to find it again after a reload. */
const keyOf = new WeakMap<HTMLElement, FocusKey>();

/**
 * The element an overlay opening now should give focus back to: the focused
 * element, or, when focus has fallen to the page, the last element that held it.
 *
 * @param dialog the overlay being opened, so a dialog chained from it can find
 *   its way back (QA D1260)
 */
export function captureOpener(dialog?: HTMLElement): HTMLElement | null {
  if (typeof document === 'undefined') return null;
  const active = document.activeElement;
  let opener: HTMLElement | null = null;
  if (active instanceof HTMLElement && active !== document.body) opener = active;
  else if (lastFocused?.isConnected) opener = lastFocused;
  if (opener) {
    keyOf.set(opener, focusKeyOf(opener));
    const outer = enclosingDialogOpener(opener);
    if (outer) fallbackOf.set(opener, outer);
  }
  if (dialog) openerOfDialog.set(dialog, opener);
  return opener;
}

/** The opener of the registered dialog that holds `el`, if any. */
function enclosingDialogOpener(el: HTMLElement): HTMLElement | null {
  for (let node = el.parentElement; node !== null; node = node.parentElement) {
    if (openerOfDialog.has(node)) return openerOfDialog.get(node) ?? null;
  }
  return null;
}

/**
 * Give focus back to `opener` if it is still on the page; if it left with a
 * dialog that closed with this one, to that dialog's opener (QA D1260).
 */
export function restoreFocus(opener: HTMLElement | null): void {
  let target = opener;
  const seen = new Set<HTMLElement>();
  const keys: FocusKey[] = [];
  while (target && !target.isConnected && !seen.has(target)) {
    seen.add(target);
    const key = keyOf.get(target);
    if (key) keys.push(key);
    target = fallbackOf.get(target) ?? null;
  }
  if (target?.isConnected && typeof target.focus === 'function') {
    target.focus();
    return;
  }
  // QA D1283 / D1284: a successful action reloads the list the opener sat in
  // (it is destroyed with the table while the screen loads): the focus rescue
  // gives focus back to its twin when the list is back, never the page host.
  awaitReturn(keys);
}
