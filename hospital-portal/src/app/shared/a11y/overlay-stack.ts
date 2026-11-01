/**
 * QA D1209 / D1201 (round 23, a regression of the D1201 fix; the D1122 class):
 * a dialog opened from INSIDE another dialog rendered UNDER it. "Lever" in the
 * bed board's Précautions dialog opened the shared reason dialog while the
 * Précautions dialog stayed open; the bed-board stylesheet gives its `.modal`
 * z-index 1001 and the shared reason dialog's backdrop is 1000, so the
 * reason's "Lever" button sat under the Précautions box and a mouse could not
 * lift a precaution. No z-index written in one stylesheet can order two
 * dialogs from different components.
 *
 * One stack orders every open dialog: each dialog directive (appModalDialog,
 * appDialogA11y, appDialogFocus — and so the shared reason, confirm and
 * discard dialogs built on them) opens a layer here. A layer opened while
 * others are open is lifted ABOVE all of them when it renders: its layer root
 * (the outermost fixed-position element around the dialog box, stopping at
 * the dialog underneath) and the cover right before it, if any, get a z-index
 * one above the highest open layer's. A dialog opened alone is never touched.
 *
 * The same stack answers "which dialog owns the keyboard": only the top layer,
 * whatever directive built it (two directive-specific stacks let a side panel
 * under a confirmation pull Tab focus back into itself).
 */

const open: OverlayLayer[] = [];

/** A page cover written right before its dialog (`.modal-backdrop` + `.modal`). */
const COVER = /(?:^|[\s-])(?:backdrop|overlay|scrim)(?:\s|$)/;

function zIndexOf(el: Element): number {
  const z = Number.parseInt(getComputedStyle(el).zIndex, 10);
  return Number.isNaN(z) ? 0 : z;
}

function isFixed(el: Element): boolean {
  return getComputedStyle(el).position === 'fixed';
}

export class OverlayLayer {
  private root: HTMLElement | null = null;
  private cover: HTMLElement | null = null;

  constructor(private readonly host: HTMLElement) {
    open.push(this);
  }

  /** The dialog on top of every other open one: it alone answers the keyboard. */
  isTop(): boolean {
    return open.at(-1) === this;
  }

  /**
   * Lift this dialog above every dialog opened before it and still open.
   * Call once the dialog is in the document (ngAfterViewInit).
   */
  raise(): void {
    const at = open.indexOf(this);
    const below = open.slice(0, at).filter((layer) => layer.host.isConnected);
    if (below.length === 0) return;
    this.locate(below);
    const root = this.root;
    if (root === null) return;
    const floor = Math.max(...below.map((layer) => layer.top()));
    if (this.top() > floor) return;
    if (this.cover !== null) {
      this.cover.style.zIndex = String(floor + 1);
      root.style.zIndex = String(floor + 2);
    } else {
      root.style.zIndex = String(floor + 1);
    }
  }

  close(): void {
    const at = open.indexOf(this);
    if (at >= 0) open.splice(at, 1);
  }

  /** The highest z-index this layer paints at. */
  private top(): number {
    if (this.root === null) this.locate([]);
    const els = [this.root, this.cover].filter((el): el is HTMLElement => el !== null);
    return els.length === 0 ? 0 : Math.max(...els.map(zIndexOf));
  }

  /**
   * The layer root: the outermost fixed-position element from the dialog box
   * up, never climbing into an element that holds a dialog underneath (a
   * dialog written inside another one is lifted inside it). The cover: a
   * fixed backdrop written as the root's previous sibling.
   */
  private locate(below: OverlayLayer[]): void {
    let root: HTMLElement | null = null;
    for (
      let el: HTMLElement | null = this.host;
      el !== null && el !== document.body && el !== document.documentElement;
      el = el.parentElement
    ) {
      if (below.some((layer) => el!.contains(layer.host))) break;
      if (isFixed(el)) root = el;
    }
    this.root = root;
    const prev = root?.previousElementSibling;
    this.cover =
      prev instanceof HTMLElement &&
      COVER.test(prev.className) &&
      isFixed(prev) &&
      !below.some((layer) => prev.contains(layer.host))
        ? prev
        : null;
  }
}

/** For specs: no layer may outlive the test that opened it. */
export function openLayerCount(): number {
  return open.length;
}
