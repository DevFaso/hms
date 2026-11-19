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
 */
export interface LayoutOffender {
  kind: 'page-hscroll' | 'clipped' | 'text-overflow';
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

    return out as {
      kind: 'page-hscroll' | 'clipped' | 'text-overflow';
      selector: string;
      detail: string;
    }[];
  });
}

export const LAYOUT_WIDTHS = [1366, 1024, 768, 390] as const;

/** Run the audit at every width, resizing in place (no reload: keeps the guard fast). */
export async function auditAtWidths(
  page: Page,
  where: string,
): Promise<{ where: string; width: number; offender: LayoutOffender }[]> {
  const found: { where: string; width: number; offender: LayoutOffender }[] = [];
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: width < 600 ? 844 : 900 });
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
