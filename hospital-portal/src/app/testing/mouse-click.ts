/**
 * QA D1209 / D1122: click the way a mouse does. A spec that calls a handler,
 * or `button.click()`, passes even when the button is painted UNDER another
 * dialog and no user can reach it. This asks the browser which element is
 * under the centre of the VISIBLE part of `el` (`elementFromPoint`, the hit
 * test a real click uses) and clicks THAT element.
 */

interface Box {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

function intersect(a: Box, b: Box): Box {
  return {
    left: Math.max(a.left, b.left),
    top: Math.max(a.top, b.top),
    right: Math.min(a.right, b.right),
    bottom: Math.min(a.bottom, b.bottom),
  };
}

/** The part of `el` the user can see: inside the viewport and every scrolling ancestor. */
function visibleBox(el: Element): Box | null {
  let box: Box = el.getBoundingClientRect();
  box = intersect(box, { left: 0, top: 0, right: innerWidth, bottom: innerHeight });
  for (let p = el.parentElement; p !== null; p = p.parentElement) {
    const style = getComputedStyle(p);
    if (style.overflowX !== 'visible' || style.overflowY !== 'visible') {
      box = intersect(box, p.getBoundingClientRect());
    }
  }
  return box.right > box.left && box.bottom > box.top ? box : null;
}

/** The element a mouse click on the visible centre of `el` lands on. */
export function hitAt(el: Element): Element | null {
  el.scrollIntoView({ block: 'center', inline: 'nearest' });
  const box = visibleBox(el);
  if (box === null) return null;
  return document.elementFromPoint((box.left + box.right) / 2, (box.top + box.bottom) / 2);
}

/** A short description of an element for an assertion message. */
function describe(el: Element | null): string {
  if (el === null) return 'nothing (it is off screen)';
  const cls =
    typeof el.className === 'string' && el.className
      ? `.${el.className.trim().split(/\s+/).join('.')}`
      : '';
  return `<${el.tagName.toLowerCase()}${cls}>`;
}

/**
 * Clicks `el` with the mouse: throws, naming what covers it, when something
 * else is on top.
 */
export function mouseClick(el: HTMLElement): void {
  const hit = hitAt(el);
  if (hit === null || !el.contains(hit)) {
    throw new Error(`${describe(el)} is covered by ${describe(hit)}: a mouse cannot reach it`);
  }
  (hit as HTMLElement).click();
}
