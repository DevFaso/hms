import type { Page } from '@playwright/test';

/**
 * QA D2230 / D2231 guard: measures the rendered layout and names every element
 * that breaks it. Three failure kinds:
 *
 *  - `page-hscroll`  the document, or the shell's scrolling `.content` area,
 *                    scrolls sideways (something is wider than the screen);
 *  - `clipped`       a visible button / link / field inside a drawer, modal,
 *                    panel or card is cut off sideways by an ancestor that hides
 *                    overflow, or by one whose sideways scrollbar is buried at
 *                    the bottom of a tall scroller (the invoice drawer's
 *                    « Contre-passer paiement »: technically scrollable, in
 *                    practice unreachable). A short local scroller around a
 *                    table (`.table-scroll`, `.data-table`) is fine: its
 *                    scrollbar sits right under the rows;
 *  - `text-overflow` text, or a card's content, spills past its own box
 *                    (scrollWidth > clientWidth + 1 on a box that does not
 *                    scroll and is not an intentional ellipsis).
 *
 *  A control inside a table row (`td`) is held to more: it must be on screen
 *  without scrolling its table sideways at all (QA D2321: the invoice list's
 *  pay / e-mail / edit buttons sat past a scrollbar at the bottom of the rows).
 *
 *  r64 (D2461 / D2462) adds three:
 *  - `hidden-columns`  at 1024 px and wider, a table column is past the edge of
 *                      its scroll box or under the sticky actions column;
 *  - `no-scroll-cue`   narrower, a table scrolls in place with nothing showing
 *                      that it does (no sticky-cell shadow, no fading edge);
 *  - `dialog-overflow` a dialog is wider than the screen;
 *  - `dialog-height` a dialog runs above or below the screen, or keeps an action
 *    button where nothing scrolls to it (QA D2938);
 *  - `squeezed-text`   a text box squeezed so narrow it prints one letter per
 *                      line (D2431: nothing spills, so `text-overflow` misses it).
 */
export interface LayoutOffender {
  kind:
    | 'page-hscroll'
    | 'clipped'
    | 'text-overflow'
    | 'hidden-columns'
    | 'no-scroll-cue'
    | 'dialog-overflow'
    | 'dialog-height'
    | 'squeezed-text';
  selector: string;
  detail: string;
}

export async function auditLayout(page: Page): Promise<LayoutOffender[]> {
  return page.evaluate(() => {
    const out: { kind: string; selector: string; detail: string }[] = [];
    const seen = new Set<string>();

    function describe(el: Element): string {
      const parts: string[] = [];
      let node: Element | null = el;
      for (let depth = 0; node && depth < 4 && node !== document.body; depth++) {
        let part = node.tagName.toLowerCase();
        if (node.id) part += `#${node.id}`;
        const testId = node.getAttribute('data-testid');
        if (testId) part += `[data-testid="${testId}"]`;
        const classes = Array.from(node.classList)
          .filter((c) => !c.startsWith('ng-'))
          .slice(0, 2);
        if (classes.length) part += `.${classes.join('.')}`;
        parts.unshift(part);
        if (node.id || testId) break;
        node = node.parentElement;
      }
      const text = (el.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 40);
      return `${parts.join(' > ')}${text ? ` «${text}»` : ''}`;
    }

    function push(kind: string, el: Element, detail: string) {
      const selector = describe(el);
      const key = `${kind}|${selector}`;
      if (seen.has(key)) return;
      seen.add(key);
      out.push({ kind, selector, detail });
    }

    function visible(el: Element): boolean {
      const rect = el.getBoundingClientRect();
      if (rect.width <= 1 || rect.height <= 1) return false;
      const style = getComputedStyle(el);
      if (style.visibility === 'hidden' || Number(style.opacity) === 0) return false;
      return !el.closest('[hidden], [aria-hidden="true"], .sr-only, .visually-hidden');
    }

    // (a) sideways scroll of the page itself.
    const root = document.documentElement;
    if (root.scrollWidth > root.clientWidth + 1) {
      push('page-hscroll', root, `${root.scrollWidth}px > ${root.clientWidth}px`);
    }
    const content = document.querySelector('.content');
    if (content && content.scrollWidth > content.clientWidth + 1) {
      // Name what pokes out: the widest descendant reaching past the edge.
      const edge = content.getBoundingClientRect().right;
      let widest: Element = content;
      let widestRight = edge;
      // Only boxes that really reach out: skip anything inside its own scroller.
      const contained = (el: Element) => {
        for (let a = el.parentElement; a && a !== content; a = a.parentElement) {
          if (getComputedStyle(a).overflowX !== 'visible') return true;
        }
        return false;
      };
      content.querySelectorAll('*').forEach((el) => {
        const right = el.getBoundingClientRect().right;
        if (right > widestRight + 1 && visible(el) && !contained(el)) {
          widest = el;
          widestRight = right;
        }
      });
      push(
        'page-hscroll',
        widest,
        `.content ${content.scrollWidth}px > ${content.clientWidth}px (reaches ${Math.round(widestRight)}px)`,
      );
    }

    // (b) interactive elements cut off sideways inside a drawer / modal / panel / card.
    const CONTAINER =
      '[role="dialog"], [aria-modal="true"], .modal, [class*="drawer"], [class*="panel"], [class*="card"], [class*="modal"], [class*="overlay"]';
    const interactive = document.querySelectorAll(
      'button, a[href], input:not([type="hidden"]), select, textarea, [role="button"]',
    );
    interactive.forEach((el) => {
      const inRow = !!el.closest('td');
      if ((!el.closest(CONTAINER) && !inRow) || !visible(el)) return;
      const rect = el.getBoundingClientRect();
      let handled = false;
      let anc: Element | null = getComputedStyle(el).position === 'fixed' ? null : el.parentElement;
      while (anc && anc !== document.body) {
        const style = getComputedStyle(anc);
        const ox = style.overflowX;
        if (ox !== 'visible') {
          const box = anc.getBoundingClientRect();
          const left = box.left + anc.clientLeft;
          const right = left + anc.clientWidth;
          const outside = rect.right > right + 1 || rect.left < left - 1;
          if (outside) {
            const scrolls = ox === 'auto' || ox === 'scroll';
            const buried = anc.scrollHeight > anc.clientHeight + 1;
            if (!scrolls) {
              push('clipped', el, `cut by ${describe(anc)} (overflow-x ${ox})`);
            } else if (inRow) {
              // QA D2321: a row's pay / edit / delete control must be on screen
              // without scrolling the table sideways (sticky actions column).
              push(
                'clipped',
                el,
                `row control off the side of ${describe(anc)} (needs sideways scroll)`,
              );
            } else if (buried) {
              push(
                'clipped',
                el,
                `off the side of ${describe(anc)}, whose sideways scrollbar is below ${anc.scrollHeight - anc.clientHeight}px of vertical scroll`,
              );
            }
            handled = true;
            break;
          }
        }
        anc = style.position === 'fixed' ? null : anc.parentElement;
      }
      // Nothing between it and a position:fixed box (a drawer) or the page cut
      // it off: then only the viewport can.
      if (!handled && (rect.right > window.innerWidth + 1 || rect.left < -1)) {
        push(
          'clipped',
          el,
          `off the side of the viewport (${Math.round(rect.left)}–${Math.round(rect.right)}px)`,
        );
      }
    });

    // (c) text or card content spilling past its own box.
    document.querySelectorAll('body *').forEach((el) => {
      if (!(el instanceof HTMLElement)) return;
      const tag = el.tagName;
      if (
        ['SCRIPT', 'STYLE', 'SVG', 'svg', 'PATH', 'path', 'TABLE', 'TBODY', 'THEAD', 'TR'].includes(
          tag,
        )
      )
        return;
      const cls = typeof el.className === 'string' ? el.className : '';
      if (/material-(symbols|icons)/.test(cls)) return;
      const ownText = Array.from(el.childNodes).some(
        (n) => n.nodeType === Node.TEXT_NODE && (n.textContent ?? '').trim().length > 0,
      );
      const cardLike = /(^|\s)[\w-]*(card|kpi|stat|tile|metric)[\w-]*(\s|$)/.test(cls);
      if (!ownText && !cardLike) return;
      // QA D2431 (r64): a text box squeezed to a sliver prints one letter per
      // line (the insured invoice card on a phone). Nothing spills, so the
      // overflow test below cannot see it: a box with words in it, narrower
      // than three letters and taller than four lines, is a failure.
      if (ownText && (el.textContent ?? '').trim().length >= 6) {
        const box = el.getBoundingClientRect();
        const st = getComputedStyle(el);
        const font = parseFloat(st.fontSize) || 14;
        const line = parseFloat(st.lineHeight) || font * 1.3;
        if (
          box.height > 1 &&
          box.width < font * 2 &&
          box.height > line * 4 &&
          st.writingMode.startsWith('horizontal') &&
          !el.closest('[hidden], [aria-hidden="true"], .sr-only, .visually-hidden')
        ) {
          push(
            'squeezed-text',
            el,
            `${Math.round(box.width)}px wide, ${Math.round(box.height)}px tall: one letter per line`,
          );
          return;
        }
      }
      if (el.scrollWidth <= el.clientWidth + 1 || el.clientWidth === 0) return;
      const style = getComputedStyle(el);
      if (style.display === 'inline' || style.display === 'contents') return;
      if (style.overflowX === 'auto' || style.overflowX === 'scroll') return;
      if (style.textOverflow === 'ellipsis') return;
      if (el.closest('.cdk-visually-hidden, .sr-only, .visually-hidden')) return;
      if (!visible(el)) return;
      // Inputs report their value's width; a long typed value scrolls inside the field.
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(tag)) return;
      push(
        'text-overflow',
        el,
        `scrollWidth ${el.scrollWidth}px > clientWidth ${el.clientWidth}px`,
      );
    });

    // (d) QA D2462 / D2321 / D2400 / D2436 (r64): a table wider than its scroll
    // box. At a desktop width (1024 px and up) every column must be on screen —
    // the sticky actions column must not sit over Statut and the money columns.
    // Narrower, the table may scroll in place, but only with a visible cue: the
    // sticky actions cell's shadow, or the scroll box's fading edge (styles.scss,
    // "scroll cue"). A column under the sticky cell counts as hidden.
    const desktop = window.innerWidth >= 1024;
    document.querySelectorAll('table').forEach((table) => {
      if (!visible(table)) return;
      let scroller: Element | null = table.parentElement;
      while (scroller && scroller !== document.body) {
        if (scroller.classList.contains('content')) return; // page scroll: rule (a)
        const ox = getComputedStyle(scroller).overflowX;
        if (ox !== 'visible' && ox !== 'clip') break;
        scroller = scroller.parentElement;
      }
      if (!scroller || scroller === document.body) return;
      if (scroller.scrollWidth <= scroller.clientWidth + 1) return;
      const box = scroller.getBoundingClientRect();
      const viewRight = box.left + scroller.clientLeft + scroller.clientWidth;
      const head = table.tHead?.rows[0] ?? table.rows[0];
      if (!head) return;
      const cells = Array.from(head.cells);
      const sticky = cells.filter((c) => getComputedStyle(c).position === 'sticky');
      const edge = Math.min(viewRight, ...sticky.map((c) => c.getBoundingClientRect().left));
      const hidden = cells
        .filter((c) => !sticky.includes(c) && c.getBoundingClientRect().right > edge + 2)
        .map((c) => (c.textContent ?? '').replace(/\s+/g, ' ').trim() || '(no header)');
      if (!hidden.length) return;
      if (desktop) {
        push(
          'hidden-columns',
          table,
          `columns ${hidden.join(', ')} hidden in ${describe(scroller)} at a desktop width ` +
            `(table ${scroller.scrollWidth}px in ${scroller.clientWidth}px; ` +
            cells
              .map(
                (c) =>
                  `${(c.textContent ?? '').trim().slice(0, 14)} ${Math.round(c.getBoundingClientRect().width)}`,
              )
              .join(' | ') +
            ')',
        );
        return;
      }
      const shadowed = sticky.some((c) => getComputedStyle(c).boxShadow !== 'none');
      const faded =
        parseFloat(getComputedStyle(scroller).getPropertyValue('--scroll-fade') || '0') > 0;
      if (!shadowed && !faded) {
        push(
          'no-scroll-cue',
          table,
          `columns ${hidden.join(', ')} scroll in ${describe(scroller)} with no visible cue`,
        );
      }
    });

    // (e) QA D2461 (r64): a dialog is never wider than the screen.
    document
      .querySelectorAll(
        '[role="dialog"], [aria-modal="true"], .modal, .modal-content, .modal-panel, .modal-card, .modal-box, .dialog-box, .dialog-panel',
      )
      .forEach((el) => {
        if (!visible(el)) return;
        const rect = el.getBoundingClientRect();
        if (rect.left < -1 || rect.right > window.innerWidth + 1) {
          push(
            'dialog-overflow',
            el,
            `dialog ${Math.round(rect.width)}px spans ${Math.round(rect.left)}–${Math.round(rect.right)}px in a ${window.innerWidth}px screen`,
          );
        }
      });

    // (f) QA D2938 (r66): a dialog is never taller than the screen, and what it
    // holds can be reached. The vitals dialog ran from -109 to 877 px on a 768 px
    // screen with nothing that scrolled, so « Enregistrer » could not be clicked.
    const scrolls = (el: Element) => {
      const overflow = getComputedStyle(el).overflowY;
      return (
        (overflow === 'auto' || overflow === 'scroll') && el.scrollHeight > el.clientHeight + 1
      );
    };
    document
      .querySelectorAll(
        '[role="dialog"][aria-modal="true"]:not([class*="drawer"], [class*="panel"], [class*="overlay"]), .modal, .modal-content, .modal-panel, .modal-card, .modal-box, .dialog-box, .dialog-panel',
      )
      .forEach((el) => {
        if (!visible(el)) return;
        const rect = el.getBoundingClientRect();
        if (rect.top < -1 || rect.bottom > window.innerHeight + 1) {
          push(
            'dialog-height',
            el,
            `dialog ${Math.round(rect.height)}px spans ${Math.round(rect.top)}–${Math.round(rect.bottom)}px in a ${window.innerHeight}px-high screen`,
          );
          return;
        }
        // Inside the screen: every button is on screen, or in a box that scrolls to it.
        el.querySelectorAll('button, [role="button"], input[type="submit"]').forEach((button) => {
          if (!visible(button)) return;
          const b = button.getBoundingClientRect();
          if (b.top >= rect.top - 1 && b.bottom <= rect.bottom + 1) return;
          let scroller: Element | null = button.parentElement;
          while (scroller && scroller !== el.parentElement) {
            if (scrolls(scroller)) return;
            scroller = scroller.parentElement;
          }
          push(
            'dialog-height',
            button,
            `button at ${Math.round(b.top)}–${Math.round(b.bottom)}px is outside its dialog (${Math.round(rect.top)}–${Math.round(rect.bottom)}px) and nothing scrolls to it`,
          );
        });
      });

    return out as LayoutOffender[];
  });
}

export const LAYOUT_WIDTHS = [1366, 1024, 768, 390] as const;

/**
 * The screen height each width is measured at. QA D2938 (r66): 1366 is the
 * 1366×768 laptop the wards use, not a 900 px desktop: a dialog that fits in
 * 900 px can still put its Save button under a laptop's screen. A phone is
 * 390×844.
 */
export function heightFor(width: number): number {
  if (width < 600) return 844;
  return width === 1366 ? 768 : 900;
}

/** Run the audit at every width, resizing in place (no reload: keeps the guard fast). */
export async function auditAtWidths(
  page: Page,
  where: string,
): Promise<{ where: string; width: number; offender: LayoutOffender }[]> {
  const found: { where: string; width: number; offender: LayoutOffender }[] = [];
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    // A phone opens with the navigation drawer closed; resizing a desktop page
    // down leaves it open over the content. Close it as a phone user would.
    await page.evaluate(() => {
      const backdrop = document.querySelector<HTMLElement>('.sidebar-backdrop');
      if (backdrop && getComputedStyle(backdrop).display !== 'none') backdrop.click();
    });
    // A drawer sliding in is measured where it lands, not mid-flight.
    await page.evaluate(() =>
      Promise.race([
        Promise.all(document.getAnimations().map((a) => a.finished.catch(() => undefined))),
        new Promise((r) => setTimeout(r, 1500)),
      ]),
    );
    // Two frames: one for the resize, one for any ResizeObserver-driven re-layout.
    await page.evaluate(
      () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))),
    );
    for (const offender of await auditLayout(page)) found.push({ where, width, offender });
  }
  return found;
}

export function formatOffenders(
  list: { where: string; width: number; offender: LayoutOffender }[],
): string {
  return list
    .map(
      (o) =>
        `[${o.offender.kind}] ${o.where} @${o.width}px: ${o.offender.selector} — ${o.offender.detail}`,
    )
    .join('\n');
}
