/**
 * QA r65 (design audit): three conventions every screen should follow, applied
 * to the rendered page in ONE place instead of in 300 templates. Each was
 * found on every module, and each would be forgotten on the next screen:
 *
 * - D2798 / E1 — a table reads as cards on a phone. Under 600 px the global
 *   table layer (styles.scss, "card mode") shows each row as a card and each
 *   cell as « Label   value ». The label is the column's header, which CSS
 *   cannot reach from a cell: {@link labelTable} copies it onto every cell as
 *   `data-label` and marks the table `data-cards`. A table with no single
 *   header row (a calendar, a matrix with spanning headers) is left as a
 *   table and scrolls in place.
 * - D2792 — an icon is not text. A Material icon is a ligature: its element's
 *   text is the icon's English name, so a screen reader spoke « calendar_month
 *   Mes rendez-vous » for a heading and « unfold_more » after a sortable
 *   column. {@link hideIcon} sets `aria-hidden="true"` on every icon, except
 *   one that is the only content of a control with no other name (hiding it
 *   would leave the control nameless; the a11y-names gate reports those).
 * - D2807 / D2803 — an icon-only button has a tooltip. Its `aria-label` is
 *   copied to `title`, so a sighted mouse user can read what « ✎ » does. A
 *   sidebar label cut by an ellipsis gets its full text as a tooltip too.
 *
 * Installed at bootstrap (app.config.ts) like focus-rescue.ts; it follows the
 * page with a MutationObserver and works in batches, one per animation frame.
 * It only writes attributes, never text or structure, and never an attribute
 * a template already set.
 */

const ICON =
  '.material-symbols-outlined, .material-symbols-rounded, .material-icons, .material-icons-outlined';
const CONTROL =
  'button, a, summary, [role="button"], [role="tab"], [role="menuitem"], [role="link"]';
/** Tables the card mode leaves alone: FullCalendar's layout tables, a date picker, an explicit opt-out. */
const NOT_A_LIST = '.fc, .mat-calendar, [data-no-cards]';

let installedOn: Document | null = null;
let observer: MutationObserver | null = null;
let frame = 0;

const squash = (text: string | null | undefined): string =>
  (text ?? '').replaceAll(/\s+/g, ' ').trim();

/** The text of `el` a reader sees: icon ligatures and visually hidden text left out. */
export function visibleText(el: Element): string {
  let out = '';
  for (const node of Array.from(el.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) out += node.textContent ?? '';
    else if (node instanceof Element && !node.matches(ICON) && !node.matches('.sr-only')) {
      out += ` ${visibleText(node)}`;
    }
  }
  return squash(out);
}

/** The header a cell in column `column` answers to; the text a screen reader hears, hidden text included. */
function headerLabel(th: HTMLTableCellElement): string {
  const explicit = th.getAttribute('data-label') ?? th.getAttribute('aria-label');
  if (explicit !== null) return squash(explicit);
  let out = '';
  const walk = (el: Element) => {
    for (const node of Array.from(el.childNodes)) {
      if (node.nodeType === Node.TEXT_NODE) out += node.textContent ?? '';
      else if (node instanceof Element && !node.matches(ICON)) {
        out += ' ';
        walk(node);
      }
    }
  };
  walk(th);
  return squash(out);
}

function setAttr(el: Element, name: string, value: string): void {
  if (el.getAttribute(name) !== value) el.setAttribute(name, value);
}

/**
 * Marks a list table for the card mode and labels its cells. Returns whether
 * the table is a card table.
 */
export function labelTable(table: HTMLTableElement): boolean {
  const head = table.tHead;
  const eligible =
    !table.closest(NOT_A_LIST) &&
    head !== null &&
    head.rows.length === 1 &&
    head.rows[0].cells.length >= 2 &&
    Array.from(head.rows[0].cells).every((c) => c.colSpan === 1 && c.rowSpan === 1);
  if (!eligible) {
    if (table.hasAttribute('data-cards')) table.removeAttribute('data-cards');
    return false;
  }
  const labels = Array.from(head.rows[0].cells).map(headerLabel);
  for (const body of Array.from(table.tBodies)) {
    for (const row of Array.from(body.rows)) {
      // A rowspan shifts the columns of the rows under it: such a row keeps no labels.
      let column = 0;
      for (const cell of Array.from(row.cells)) {
        if (cell.colSpan > 1 || cell.rowSpan > 1) {
          if (cell.hasAttribute('data-label')) cell.removeAttribute('data-label');
          column += cell.colSpan;
          continue;
        }
        setAttr(cell, 'data-label', labels[column] ?? '');
        column++;
      }
    }
  }
  setAttr(table, 'data-cards', '');
  return true;
}

/** `aria-hidden` on an icon, unless it is the whole name of its control. */
export function hideIcon(icon: Element): void {
  if (
    icon.hasAttribute('aria-hidden') ||
    icon.hasAttribute('aria-label') ||
    icon.getAttribute('role') === 'img'
  ) {
    return;
  }
  const control = icon.closest(CONTROL);
  if (
    control &&
    !control.hasAttribute('aria-label') &&
    !control.hasAttribute('aria-labelledby') &&
    !control.hasAttribute('title') &&
    visibleText(control) === '' &&
    control.querySelector('.sr-only, img[alt]:not([alt=""])') === null
  ) {
    return;
  }
  icon.setAttribute('aria-hidden', 'true');
}

/** An icon-only control: its accessible name is also its tooltip. */
export function titleIconButton(control: Element): void {
  if (control.hasAttribute('title')) return;
  const label = squash(control.getAttribute('aria-label'));
  if (label === '' || control.querySelector(ICON) === null) return;
  if (visibleText(control) !== '') return;
  control.setAttribute('title', label);
}

/** A sidebar label cut by an ellipsis shows its full text on hover. */
function titleTruncatedNavLabel(label: HTMLElement): void {
  const host = label.closest('a, button') ?? label;
  const text = squash(label.textContent);
  const cut = label.scrollWidth > label.clientWidth + 1;
  if (cut) {
    if (!host.hasAttribute('title') || host.hasAttribute('data-auto-title')) {
      setAttr(host, 'title', text);
      setAttr(host, 'data-auto-title', '');
    }
  } else if (host.hasAttribute('data-auto-title')) {
    host.removeAttribute('title');
    host.removeAttribute('data-auto-title');
  }
}

/** One pass over the page. Exported for the specs; the observer calls it per frame. */
export function applyDesignConventions(doc: Document): void {
  for (const table of Array.from(doc.querySelectorAll('table'))) labelTable(table);
  for (const icon of Array.from(doc.querySelectorAll(ICON))) hideIcon(icon);
  for (const control of Array.from(
    doc.querySelectorAll('button[aria-label], a[aria-label], [role="button"][aria-label]'),
  )) {
    titleIconButton(control);
  }
  for (const label of Array.from(doc.querySelectorAll<HTMLElement>('.nav-label'))) {
    titleTruncatedNavLabel(label);
  }
}

export function installDesignConventions(doc: Document): void {
  if (installedOn === doc) return;
  uninstallDesignConventions();
  installedOn = doc;
  const view = doc.defaultView;
  if (!view || typeof MutationObserver === 'undefined') return;
  outsideAngular(() => {
    const schedule = () => {
      if (frame !== 0) return;
      frame = view.requestAnimationFrame(() => {
        frame = 0;
        if (installedOn === doc) applyDesignConventions(doc);
      });
    };
    // Attribute writes are not observed, so the pass never re-triggers itself.
    observer = new MutationObserver(schedule);
    observer.observe(doc.documentElement, { childList: true, subtree: true, characterData: true });
    view.addEventListener('resize', schedule);
    resizeTarget = view;
    resizeListener = schedule;
    schedule();
  });
}

let resizeTarget: Window | null = null;
let resizeListener: (() => void) | null = null;

/**
 * zone.js patches MutationObserver, requestAnimationFrame and addEventListener:
 * created in the root zone, a pass never starts a change-detection run (it
 * writes attributes only, so Angular has nothing to re-render).
 */
function outsideAngular(work: () => void): void {
  const zone = (globalThis as { Zone?: { root: { run(fn: () => void): void } } }).Zone;
  if (zone) zone.root.run(work);
  else work();
}

/** Test seam: the document the conventions follow (app.config.spec.ts). */
export function designConventionsInstalledOn(): Document | null {
  return installedOn;
}

export function uninstallDesignConventions(): void {
  observer?.disconnect();
  observer = null;
  if (resizeTarget && resizeListener) resizeTarget.removeEventListener('resize', resizeListener);
  if (frame !== 0 && installedOn?.defaultView) installedOn.defaultView.cancelAnimationFrame(frame);
  frame = 0;
  resizeTarget = null;
  resizeListener = null;
  installedOn = null;
}
