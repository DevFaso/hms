/**
 * QA D1202 / D1198 / D1199 / D1197 (round 22; the D1146 / D1125 class of
 * rounds 20–21): after a submit, a panel toggle or a close, the keyboard fell
 * to the page body. The next Tab started again from the top of the page, and
 * a screen reader announced nothing.
 *
 * Every instance was the same event: the control that held focus went away
 * (a submitted dialog closed and the row that opened it was re-rendered; the
 * "Configurer TOTP" button was replaced by the QR panel; the recall's
 * "Fermer" left with its row; "Révoquer" removed the family member). Round
 * 20 and 21 fixed the screens they named, one handler at a time, and the next
 * round found the class on the screens nobody had named.
 *
 * So this is one choke point for the whole portal, installed at bootstrap
 * (app.config.ts): when the focused element is REMOVED from the page (or
 * disabled) and focus lands on the body, focus moves to the nearest part of
 * the page that is still there — the closest surviving ancestor of the
 * element that had it (a table, a card, a dialog), made focusable for the
 * script only (`tabindex="-1"`, no visible tab stop) — so the next Tab goes to
 * the next control in that place. A disabled control that is enabled again
 * (a submit button that disabled itself while saving) gets the focus back.
 *
 * It never takes focus the reader moved on purpose: a click on the page
 * background (focus leaves an element that is still there) disarms it until
 * the next focus. Screens that know a better target (the panel that replaced
 * the button) still focus it themselves; this runs after them and only when
 * focus is on the body.
 *
 * `scripts/lib/focus-after-action.test.mjs` fails the build if app.config.ts
 * stops installing it.
 *
 * QA D1220 (round 23): the place the rescue moves focus to is a STAND-IN, not
 * a destination. An inline editor closed by Escape or "Abandonner" knows the
 * real target (its pencil, rendered again a tick later), but it saw focus
 * already on the rescued price cell (TD) or lines TBODY — "not the body" —
 * and gave up. `focusFell()` answers "did the keyboard fall?" for every late
 * return: focus on the body OR still on a stand-in counts as fallen.
 */

/** The ancestors of the focused element, nearest first, recorded while it is on the page. */
let trail: HTMLElement[] = [];
/** The element that had focus. */
let held: HTMLElement | null = null;
/** False after the reader moved focus off a still-present element to the page on purpose. */
let armed = false;
let installedOn: Document | null = null;
/** The element focus was moved to as a stand-in (a surviving ancestor), until anything else takes focus. */
let standIn: HTMLElement | null = null;
let observer: MutationObserver | null = null;
const listeners: [string, EventListener, boolean][] = [];

function onFocusIn(event: Event): void {
  const target = event.target;
  if (!(target instanceof HTMLElement) || target === target.ownerDocument.body) return;
  if (target !== standIn) standIn = null;
  held = target;
  armed = true;
  trail = [];
  for (
    let el = target.parentElement;
    el && el !== target.ownerDocument.body;
    el = el.parentElement
  ) {
    trail.push(el);
  }
}

function onFocusOut(event: Event): void {
  const target = event.target;
  if (!(target instanceof HTMLElement)) return;
  // Decided after the event: removed elements fire focusout in some browsers.
  setTimeout(() => {
    const doc = target.ownerDocument;
    if (
      held === target &&
      target.isConnected &&
      !isDisabled(target) &&
      doc.activeElement === doc.body
    ) {
      armed = false;
    }
  }, 0);
}

function isDisabled(el: HTMLElement): boolean {
  return (el as HTMLButtonElement).disabled === true || el.closest('[inert]') !== null;
}

function focusPlace(el: HTMLElement): void {
  if (!el.hasAttribute('tabindex') && !el.matches('a[href], button, input, select, textarea')) {
    el.setAttribute('tabindex', '-1');
  }
  // Marked before focusing: the focusin it fires must not clear it.
  standIn = el;
  el.focus({ preventScroll: true });
}

/**
 * Moves focus to `el` as a stand-in: a later, more precise return (the
 * control that opened an inline form, rendered again) still takes over.
 */
export function focusStandIn(el: HTMLElement): void {
  focusPlace(el);
}

/**
 * QA D1220: has the keyboard fallen — on the page body, or still on a
 * stand-in the rescue (or `focusStandIn`) put it on? A late focus return
 * (an inline form's opener, a row editor's pencil) goes ahead only then, so
 * it never takes focus the reader moved on purpose, and never yields to a
 * stand-in.
 */
export function focusFell(doc: Document = document): boolean {
  const active = doc.activeElement;
  return active === null || active === doc.body || (standIn !== null && active === standIn);
}

/** Runs after the page changed: rescues focus that fell to the body. */
export function rescueFocus(doc: Document = document): void {
  if (!armed || !held) return;
  const active = doc.activeElement;
  if (active !== null && active !== doc.body) return;
  if (held.isConnected) {
    // Still on the page: back to it once it can take focus again.
    if (!isDisabled(held)) held.focus({ preventScroll: true });
    return;
  }
  const place = trail.find((el) => el.isConnected && !el.closest('[inert]'));
  if (place) focusPlace(place);
}

/** Installs the rescue on `doc` (once). */
export function installFocusRescue(doc: Document = document): void {
  if (installedOn === doc || typeof MutationObserver === 'undefined') return;
  uninstallFocusRescue();
  installedOn = doc;
  const add = (type: string, fn: EventListener) => {
    doc.addEventListener(type, fn, true);
    listeners.push([type, fn, true]);
  };
  add('focusin', onFocusIn);
  add('focusout', onFocusOut);
  observer = new MutationObserver(() => rescueFocus(doc));
  observer.observe(doc.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['disabled', 'inert', 'hidden'],
  });
}

/** The document the rescue is installed on (the app-start wiring spec reads it). */
export function focusRescueInstalledOn(): Document | null {
  return installedOn;
}

/** Test seam: removes the rescue and forgets what it held. */
export function uninstallFocusRescue(): void {
  if (installedOn) {
    for (const [type, fn, capture] of listeners) installedOn.removeEventListener(type, fn, capture);
  }
  listeners.length = 0;
  observer?.disconnect();
  observer = null;
  installedOn = null;
  standIn = null;
  trail = [];
  held = null;
  armed = false;
}
