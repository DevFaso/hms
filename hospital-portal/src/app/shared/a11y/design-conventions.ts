/**
 * QA r65 (design audit): three conventions every screen should follow, applied
 * to the rendered page in ONE place instead of in 300 templates. Each was
 * found on every module, and each would be forgotten on the next screen:
 *
 * - D2798 / E1 — a table reads as cards on a phone. Under 600 px the global
 *   table layer (styles.scss, "card mode") shows each row as a card and each
 *   cell as « Label   value ». The label is the column's header, which CSS
 *   cannot reach from a cell: {@link labelTable} copies it onto every cell as
 *   `data-label` and marks the table `data-cards`. A table whose cells cannot
 *   be matched to one header each (no single header row, a spanning header, a
 *   body cell that spans rows) is left as a table and scrolls in place: a
 *   value under the wrong label is worse than a sideways scroll.
 * - D2792 — an icon is not text. A Material icon is a ligature: its element's
 *   text is the icon's English name, so a screen reader spoke « calendar_month
 *   Mes rendez-vous » for a heading and « unfold_more » after a sortable
 *   column. {@link hideIcon} sets `aria-hidden="true"` on an icon that sits
 *   beside a name: in a heading, a column header, a label, or a control that
 *   has another name. A free-standing icon is left alone: it may be the only
 *   signal of its cell (an abnormal-result flag), and its English name is
 *   better than nothing.
 * - D2807 / D2803 — an icon-only button has a tooltip. Its `aria-label` is
 *   copied to `title`, so a sighted mouse user can read what « ✎ » does, and
 *   follows the label when a toggle changes it. A sidebar label cut by an
 *   ellipsis gets its full text as a tooltip too.
 *
 * Installed at bootstrap (app.config.ts) like focus-rescue.ts; it follows the
 * page with a MutationObserver and works in batches, one per animation frame.
 * It only writes attributes, never text or structure. It never overrides an
 * attribute a template set, and it takes back what it wrote when the reason
 * is gone (`data-auto-hidden`, `data-auto-title` mark its own writes).
 */

const ICON =
  '.material-symbols-outlined, .material-symbols-rounded, .material-icons, .material-icons-outlined';
const CONTROL =
  'button, a, summary, [role="button"], [role="tab"], [role="menuitem"], [role="link"]';
/** Where an icon sits beside a name: a heading, a column header, a label, a control. */
const NAMED_HOST = `h1, h2, h3, h4, h5, h6, th, [role="columnheader"], label, legend, caption, ${CONTROL}`;
/** Tables the card mode leaves alone: FullCalendar's layout tables, a date picker, an explicit opt-out. */
const NOT_A_LIST = '.fc, .mat-calendar, [data-no-cards]';
/** The navigation: its labels are measured for truncation, which costs a layout. */
const NAVIGATION = '.sidebar, nav';

let installedOn: Document | null = null;
let observer: MutationObserver | null = null;
let frame = 0;
/** Whether the next pass measures the sidebar labels (after a resize or a change in the navigation). */
let navigationChanged = true;

const squash = (text: string | null | undefined): string =>
  (text ?? '').replaceAll(/\s+/g, ' ').trim();

/** Whether `el` takes up space on the page (`display: none` at this width does not). */
function rendered(el: Element): boolean {
  return el.getClientRects().length > 0;
}

/**
 * The text of `el` besides its icons. `seen: true` is the text a sighted reader
 * sees: visually hidden text (`.sr-only`) is left out. `seen: false` is the
 * text a screen reader hears: `.sr-only` counts. Either way, text inside an
 * element that is not rendered (`display: none`, a label hidden on a phone)
 * counts for nobody.
 */
function textBesidesIcons(el: Element, seen: boolean): string {
  let out = '';
  for (const node of Array.from(el.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) out += node.textContent ?? '';
    else if (
      node instanceof Element &&
      !node.matches(ICON) &&
      !(seen && node.matches('.sr-only')) &&
      rendered(node)
    ) {
      out += ` ${textBesidesIcons(node, seen)}`;
    }
  }
  return squash(out);
}

/** The text of `el` a sighted reader sees: icon ligatures and hidden text left out. */
export function visibleText(el: Element): string {
  return textBesidesIcons(el, true);
}

/** Whether a screen reader has a name for `host` without reading its icons. */
function namedWithoutIcons(host: Element): boolean {
  return (
    squash(host.getAttribute('aria-label')) !== '' ||
    host.hasAttribute('aria-labelledby') ||
    squash(host.getAttribute('title')) !== '' ||
    textBesidesIcons(host, false) !== '' ||
    host.querySelector('img[alt]:not([alt=""])') !== null
  );
}

/**
 * The label of a column: an explicit `data-label` on the header, else the
 * header's text (visually hidden text included, icon names left out), else its
 * `aria-label`. The text comes before the `aria-label` because a sortable
 * header's label is often the command (« Trier par nom »), not the column.
 */
function headerLabel(th: HTMLTableCellElement): string {
  const explicit = th.getAttribute('data-label');
  if (explicit !== null) return squash(explicit);
  return headerText(th) || squash(th.getAttribute('aria-label'));
}

function headerText(th: HTMLTableCellElement): string {
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

/** Every body cell of a table (cells of nested tables are not its own). */
function bodyCells(table: HTMLTableElement): HTMLTableCellElement[] {
  return Array.from(table.tBodies).flatMap((body) =>
    Array.from(body.rows).flatMap((row) => Array.from(row.cells)),
  );
}

/**
 * Whether every body cell of `table` answers to exactly one header: one plain
 * header row of two columns or more, and no body cell that spans rows. A cell
 * with `rowspan` takes a column of the rows under it, so the cells of those
 * rows would be counted from the wrong column: on a phone the header row is
 * not shown, and « Dose » over a drug name is a wrong reading of a chart.
 * (A cell that spans COLUMNS is fine: it carries no label and the cells after
 * it are counted past it.)
 */
export function isCardTable(table: HTMLTableElement): boolean {
  const head = table.tHead;
  return (
    !table.closest(NOT_A_LIST) &&
    head !== null &&
    head.rows.length === 1 &&
    head.rows[0].cells.length >= 2 &&
    Array.from(head.rows[0].cells).every((c) => c.colSpan === 1 && c.rowSpan === 1) &&
    bodyCells(table).every((c) => c.rowSpan === 1)
  );
}

/**
 * Marks a list table for the card mode and labels its cells. Returns whether
 * the table is a card table. A table that stops being one (a spanning cell
 * rendered later) loses the mark and the labels this function wrote.
 */
export function labelTable(table: HTMLTableElement): boolean {
  if (!isCardTable(table)) {
    if (table.hasAttribute('data-cards')) {
      table.removeAttribute('data-cards');
      for (const cell of bodyCells(table)) cell.removeAttribute('data-label');
    }
    return false;
  }
  const labels = Array.from(table.tHead!.rows[0].cells).map(headerLabel);
  for (const body of Array.from(table.tBodies)) {
    for (const row of Array.from(body.rows)) {
      let column = 0;
      for (const cell of Array.from(row.cells)) {
        if (cell.colSpan > 1) {
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

/**
 * `aria-hidden` on an icon that sits beside a name: in a heading, a column
 * header, a label or a control that a screen reader can name without it.
 *
 * Left alone: an icon the template gave a meaning of its own (a label, a
 * title, `role="img"`, its own `aria-hidden`); an icon that is the only name
 * of its host (a control whose label is `display: none` on a phone included);
 * and a free-standing icon, which may be the only signal of its cell.
 * Re-evaluated on every pass: when the name goes (the label is hidden at this
 * width), the icon is given back to the screen reader.
 */
export function hideIcon(icon: Element): void {
  const ours = icon.hasAttribute('data-auto-hidden');
  if (
    (icon.hasAttribute('aria-hidden') && !ours) ||
    icon.hasAttribute('aria-label') ||
    icon.hasAttribute('aria-labelledby') ||
    icon.hasAttribute('title') ||
    icon.getAttribute('role') === 'img'
  ) {
    return;
  }
  const host = icon.closest(NAMED_HOST);
  const decorative = host !== null && namedWithoutIcons(host);
  if (decorative) {
    setAttr(icon, 'aria-hidden', 'true');
    setAttr(icon, 'data-auto-hidden', '');
  } else if (ours) {
    icon.removeAttribute('aria-hidden');
    icon.removeAttribute('data-auto-hidden');
  }
}

/**
 * An icon-only control: its accessible name is also its tooltip. The tooltip
 * follows the name (a toggle that reads « Désactiver » then « Réactiver »),
 * and goes when the control is no longer icon-only. A title the template
 * wrote is never touched.
 */
export function titleIconButton(control: Element): void {
  const ours = control.hasAttribute('data-auto-title');
  if (control.hasAttribute('title') && !ours) return;
  const label = squash(control.getAttribute('aria-label'));
  const iconOnly =
    label !== '' && control.querySelector(ICON) !== null && visibleText(control) === '';
  if (iconOnly) {
    setAttr(control, 'title', label);
    setAttr(control, 'data-auto-title', '');
  } else if (ours) {
    control.removeAttribute('title');
    control.removeAttribute('data-auto-title');
  }
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

/**
 * One pass over the page. Exported for the specs; the observer calls it per
 * frame. `navigation: false` skips the sidebar labels, whose truncation is
 * measured (a layout): the observer asks for it only after a resize or a
 * change inside the navigation.
 */
export function applyDesignConventions(
  doc: Document,
  options: { navigation?: boolean } = {},
): void {
  for (const table of Array.from(doc.querySelectorAll('table'))) labelTable(table);
  for (const icon of Array.from(doc.querySelectorAll(ICON))) hideIcon(icon);
  for (const control of Array.from(
    doc.querySelectorAll(
      'button[aria-label], a[aria-label], [role="button"][aria-label], [data-auto-title]:not(.nav-label)',
    ),
  )) {
    if (control.querySelector('.nav-label') === null) titleIconButton(control);
  }
  if (options.navigation !== false) {
    for (const label of Array.from(doc.querySelectorAll<HTMLElement>('.nav-label'))) {
      titleTruncatedNavLabel(label);
    }
  }
}

export function installDesignConventions(doc: Document): void {
  if (installedOn === doc) return;
  uninstallDesignConventions();
  installedOn = doc;
  navigationChanged = true;
  const view = doc.defaultView;
  if (!view || typeof MutationObserver === 'undefined') return;
  outsideAngular(() => {
    const schedule = () => {
      if (frame !== 0) return;
      frame = view.requestAnimationFrame(() => {
        frame = 0;
        if (installedOn !== doc) return;
        const navigation = navigationChanged;
        navigationChanged = false;
        applyDesignConventions(doc, { navigation });
      });
    };
    const onResize = () => {
      navigationChanged = true;
      schedule();
    };
    // The pass writes aria-hidden, title and data-* only: of the attributes,
    // just `aria-label` is watched (a toggle renames its button), so the pass
    // never re-triggers itself.
    observer = new MutationObserver((records) => {
      if (
        !navigationChanged &&
        records.some((record) => {
          const at = record.target instanceof Element ? record.target : record.target.parentElement;
          return at?.closest(NAVIGATION) != null;
        })
      ) {
        navigationChanged = true;
      }
      schedule();
    });
    observer.observe(doc.documentElement, {
      childList: true,
      subtree: true,
      characterData: true,
      attributes: true,
      attributeFilter: ['aria-label'],
    });
    view.addEventListener('resize', onResize);
    resizeTarget = view;
    resizeListener = onResize;
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
