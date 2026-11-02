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
 *
 * QA D1283 / D1284 (round 27; the D1004 / D1260 / D1270 sibling): after a
 * SUCCESSFUL dialog action the screen reloads its list under a loading flag
 * (`@if (!loading())`), so the opener (the row's "Donner", the lab order's
 * void button) is destroyed with the whole table, the nearest surviving
 * ancestor is the page component itself, and focus ended on
 * `APP-NURSE-STATION` / `APP-LAB` (or the eMAR's `SECTION`). Escape worked
 * because nothing reloads on a cancel. Two rules, for every dialog of the
 * portal at once:
 *
 * - the control that was lost is AWAITED: when the list comes back, its twin
 *   (the same control of the same row: {@link FocusKey}) takes focus; if the
 *   control left its row (the dose was given, the order voided), the row's
 *   first action does;
 * - until then focus waits on a stand-in that is never a component host
 *   (`app-…`): the place the panel named (`data-focus-return`, selectors
 *   tried in order, `||`-separated: the active tab, the filter), else the
 *   page's heading (`h1` / `h2`). When the row never comes back (the order
 *   left the list), the stand-in is where focus stays.
 *
 * The dialog directives hand their opener to {@link awaitReturn} when it is
 * not on the page as they close (focus-return.ts `restoreFocus`).
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

/**
 * What identifies a control across a re-render (QA D1283): the same tag, the
 * same id / test id, or the same name and text, in the row with the same
 * signature. Taken while the control is on the page.
 */
export interface FocusKey {
  readonly tag: string;
  readonly id: string;
  readonly testid: string;
  readonly name: string;
  readonly row: string | null;
  /** Where a panel said focus goes when this control leaves (`data-focus-return`). */
  readonly returnTo: readonly string[];
}

/** A row of a list or table: its controls are told apart by the row's signature. */
const ROW = '[data-row-key], tr, li, [role="row"]';
const FOCUSABLE = [
  'button:not([disabled])',
  'a[href]',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex="0"]',
].join(', ');
/** How long a lost control is awaited (a reload over a slow network included). */
const AWAIT_MS = 15_000;

/**
 * The control lost with the last removal, or handed over by a closing dialog.
 * `old`: for a control the rescue saw removed (not a dialog's opener), the
 * elements already on the page then — never its twin, so a same-named button
 * elsewhere on the page cannot take the focus.
 */
let awaited: { keys: FocusKey[]; until: number; old: WeakSet<Element> | null } | null = null;
/** The key of {@link held}, taken when it received focus (its row may be gone later). */
let heldKey: FocusKey | null = null;

const squash = (text: string | null | undefined): string =>
  (text ?? '').replaceAll(/\s+/g, ' ').trim().slice(0, 160);

function nameOf(el: Element): string {
  const label = el.getAttribute('aria-label') ?? el.getAttribute('title') ?? '';
  return `${squash(label)}|${squash(el.textContent)}`;
}

function rowSignature(row: Element): string {
  const key = row.getAttribute('data-row-key');
  if (key) return `key:${key}`;
  // A table row: its first two cells (patient and drug, order number and patient).
  if (row.tagName === 'TR') {
    return [...row.children]
      .slice(0, 2)
      .map((c) => squash(c.textContent))
      .join(' | ');
  }
  return squash(row.firstElementChild?.textContent ?? row.textContent);
}

/** The key of `el`, taken while it is on the page. */
export function focusKeyOf(el: HTMLElement): FocusKey {
  const row = el.closest(ROW);
  const hint = el.closest('[data-focus-return]')?.getAttribute('data-focus-return') ?? '';
  return {
    tag: el.tagName,
    id: el.id,
    testid: el.getAttribute('data-testid') ?? '',
    name: nameOf(el),
    row: row && row !== el ? rowSignature(row) : null,
    returnTo: hint
      .split('||')
      .map((x) => x.trim())
      .filter((x) => x !== ''),
  };
}

function usable(el: Element): el is HTMLElement {
  return (
    el instanceof HTMLElement &&
    el.isConnected &&
    !isDisabled(el) &&
    el.closest('[hidden]') === null
  );
}

function isNew(el: Element): boolean {
  return !awaited?.old?.has(el);
}

function sameControl(el: HTMLElement, key: FocusKey): boolean {
  if (key.id) return el.id === key.id;
  if (key.testid) return el.dataset['testid'] === key.testid;
  return nameOf(el) === key.name;
}

function firstMatch(doc: Document, selector: string): HTMLElement | null {
  try {
    return [...doc.querySelectorAll(selector)].find(usable) ?? null;
  } catch {
    // A malformed hint is ignored, never thrown into the page.
    return null;
  }
}

/** The same control in the same row, or (gone from its row) the row's first action. */
function inSameRow(doc: Document, same: HTMLElement[], row: string): HTMLElement | null {
  const twin = same.find((el) => {
    const r = el.closest(ROW);
    return r !== null && rowSignature(r) === row;
  });
  if (twin) return twin;
  const again = [...doc.querySelectorAll(ROW)].find((r) => isNew(r) && rowSignature(r) === row);
  return again ? ([...again.querySelectorAll(FOCUSABLE)].find(usable) ?? null) : null;
}

/** The control of `key` on the page again, or (gone from its row) the row's next action. */
function twinOf(doc: Document, key: FocusKey): HTMLElement | null {
  const same = [...doc.querySelectorAll(key.tag)].filter(
    (el): el is HTMLElement => usable(el) && isNew(el) && sameControl(el, key),
  );
  if (key.row !== null) return inSameRow(doc, same, key.row);
  // Outside a row only an unambiguous control is the twin.
  return key.id || key.testid || same.length === 1 ? (same[0] ?? null) : null;
}

/** The first place a panel named for its lost controls (`data-focus-return`) that is on the page. */
function namedPlace(doc: Document): HTMLElement | null {
  for (const key of awaited?.keys ?? []) {
    for (const selector of key.returnTo) {
      const target = firstMatch(doc, selector);
      if (target) return target;
    }
  }
  return null;
}

/**
 * QA D1283: a closing dialog whose opener is not on the page (it is being
 * re-rendered with its list) hands its key here; when the twin is back and
 * focus has fallen (body or stand-in), the twin takes it.
 */
export function awaitReturn(keys: readonly FocusKey[]): void {
  if (keys.length === 0) return;
  awaited = { keys: [...keys], until: performance.now() + AWAIT_MS, old: null };
  armed = true;
}

/** Focuses the awaited control's twin if it is back; whether it did. */
function returnToTwin(doc: Document): boolean {
  if (!awaited) return false;
  if (performance.now() > awaited.until) {
    awaited = null;
    return false;
  }
  for (const key of awaited.keys) {
    const twin = twinOf(doc, key);
    if (twin) {
      awaited = null;
      twin.focus({ preventScroll: true });
      return true;
    }
  }
  return false;
}

/** A component's host element: a custom element (`app-lab`), or Angular's marks on a root. */
function isComponentHost(el: HTMLElement): boolean {
  return (
    el.tagName.includes('-') ||
    [...el.attributes].some((a) => a.name === 'ng-version' || a.name.startsWith('_nghost'))
  );
}

/** A component host is never a stand-in: its heading is (QA D1283). */
function standInFor(place: HTMLElement): HTMLElement {
  if (!isComponentHost(place)) return place;
  return [...place.querySelectorAll('h1, h2')].find(usable) ?? place;
}

function onFocusIn(event: Event): void {
  const target = event.target;
  if (!(target instanceof HTMLElement) || target === target.ownerDocument.body) return;
  if (target !== standIn) {
    standIn = null;
    // Focus went somewhere on purpose (the reader, a screen, the twin itself).
    awaited = null;
    heldKey = focusKeyOf(target);
  }
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

function stopAwaiting(): void {
  awaited = null;
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
  // Focus moved on from the stand-in (even with no rescue installed to see it).
  if (standIn !== null && active !== standIn) standIn = null;
  return active === null || active === doc.body || (standIn !== null && active === standIn);
}

/** Runs after the page changed: rescues focus that fell to the body. */
export function rescueFocus(doc: Document = document): void {
  if (!armed) return;
  // QA D1283: the lost control is back (its list reloaded): focus returns to
  // it; or the place its panel named has appeared: a better stand-in.
  if (awaited && focusFell(doc)) {
    if (returnToTwin(doc)) return;
    const named = namedPlace(doc);
    if (named && named !== standIn) {
      focusPlace(named);
      return;
    }
  }
  if (!held) return;
  const active = doc.activeElement;
  if (active !== null && active !== doc.body) return;
  if (held.isConnected) {
    // Still on the page: back to it once it can take focus again.
    if (!isDisabled(held)) held.focus({ preventScroll: true });
    return;
  }
  if (!awaited && heldKey) {
    const key = heldKey;
    awaited = {
      keys: [key],
      until: performance.now() + AWAIT_MS,
      old: new WeakSet(doc.querySelectorAll(`${key.tag}, ${ROW}`)),
    };
  }
  if (returnToTwin(doc)) return;
  // A stand-in while the list reloads: the place the panel named (the active
  // tab, the filter), else the nearest surviving part of the page. The twin
  // still takes over when it is back.
  const named = namedPlace(doc);
  if (named) {
    focusPlace(named);
    return;
  }
  const place = trail.find((el) => el.isConnected && !el.closest('[inert]'));
  if (place) focusPlace(standInFor(place));
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
  // The reader acting (a click, a key) ends any wait for a lost control.
  add('pointerdown', stopAwaiting);
  add('keydown', stopAwaiting);
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
  heldKey = null;
  awaited = null;
  armed = false;
}
