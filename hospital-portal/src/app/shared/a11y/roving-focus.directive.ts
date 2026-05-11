import {
  Directive,
  ElementRef,
  HostListener,
  Input,
  OnDestroy,
  AfterViewInit,
  inject,
} from '@angular/core';

/**
 * v1.0 row 11 finish — roving-tabindex pattern (WAI-ARIA Authoring
 * Practices §6.6) wrapped as a single directive.
 *
 * Apply to a container element. The value of the `appRovingFocus`
 * input is a CSS selector string (e.g. `"li.row"`, `"button.tab"`);
 * the directive runs `host.querySelectorAll(selector)` to find the
 * focusable descendants, then makes exactly one of them keyboard-
 * tabbable at a time. ArrowUp/ArrowDown (and ArrowLeft/ArrowRight
 * when `orientation === 'horizontal'` or `'both'`) walk between
 * items; Tab leaves the group entirely.
 *
 * Used by:
 * - Nurse-station triage vitals grid (vital input cells)
 * - Patient-tracker patient rows (cards within a column)
 * - In-basket panel item list (notification rows)
 *
 * Why a custom directive instead of `@angular/cdk/a11y`'s
 * `FocusKeyManager`?  The CDK manager requires every item to be an
 * Angular component/directive implementing `FocusableOption`. Our
 * three callers are plain `<input>`, `<li>`, and `<button>` rendered
 * by `@for`. Wrapping each one in a per-item directive plus a
 * `ContentChildren` query just to get arrow-key roving would be a
 * lot of yak-shaving for ~40 lines of vanilla DOM glue. If we ever
 * need typeahead, wrap, or active-descendant patterns we can graduate
 * to the CDK; until then this stays small and obvious.
 *
 * Contract:
 *   <ul appRovingFocus="li.row" orientation="vertical" wrap>
 *     <li class="row" *ngFor="let r of rows">…</li>
 *   </ul>
 *
 * - The first matching descendant gets `tabindex="0"`; the rest get
 *   `tabindex="-1"`. After arrow-key activation, the active item
 *   gets `tabindex="0"` and receives focus.
 * - Items that already carry an explicit `tabindex` attribute when
 *   the directive mounts are left alone — the directive marks the
 *   ones it manages with `data-rf-managed="true"` and only mutates
 *   tabindex on those. This lets a page author opt an item out of
 *   the roving group (focus-trap sentinel, externally-Tab-reachable
 *   input, etc.) by pre-setting its tabindex.
 * - We re-query items on every keystroke so dynamically added rows
 *   participate without an explicit `refresh()` call. New rows
 *   without an explicit tabindex become managed at the next
 *   `applyTabindex` call. That's O(n) but n is human-scale here
 *   (≤ ~50 items per surface).
 * - The directive does not emit selection — pressing Enter / Space
 *   on the focused item lets the native or component-level handler
 *   take over, exactly as if the user had clicked.
 *
 * See docs/ui/accessibility.md §3 (keyboard contract), §6.2 (vitals
 * grid), §6.6 (patient tracker), §6.10 (in-basket).
 */
@Directive({
  selector: '[appRovingFocus]',
  standalone: true,
})
export class RovingFocusDirective implements AfterViewInit, OnDestroy {
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);

  /** CSS selector for the focusable descendants. Required. */
  @Input({ required: true, alias: 'appRovingFocus' }) itemsSelector!: string;

  /**
   * Which arrow keys participate.
   * - `vertical` (default): ArrowUp / ArrowDown
   * - `horizontal`: ArrowLeft / ArrowRight
   * - `both`: all four (used by 2-D grids).
   */
  @Input() orientation: 'vertical' | 'horizontal' | 'both' = 'vertical';

  /**
   * When true, ArrowDown past the last item wraps to first (and vice
   * versa). Defaults to false because clamping is the safer expectation
   * for clinical worklists — wrapping an emergency-acuity badge back
   * to the top of the queue mid-keystroke is disorienting.
   */
  @Input({ transform: (v: boolean | string) => v === '' || v === true || v === 'true' })
  wrap = false;

  /**
   * Required modifier key for the arrow shortcut.
   *
   * - `'none'` (default): plain ArrowUp/Down. Use for groups whose
   *   items don't have a native arrow-key binding (rows, list items,
   *   buttons).
   * - `'alt'`: requires Alt held. Use for groups whose items are
   *   `<input type="number">` (native ArrowUp/Down increments the
   *   value) or any other element with a default arrow handler. The
   *   sidebar nav uses the same modifier (docs/ui/accessibility.md §5)
   *   so the muscle memory stays consistent.
   * - `'ctrl'`: requires Ctrl held. Reserved for future use.
   *
   * On AZERTY keyboards Alt is on the same physical key as US-layout
   * Alt, so the shortcut is layout-stable.
   */
  @Input() modifier: 'none' | 'alt' | 'ctrl' = 'none';

  ngAfterViewInit(): void {
    // Initialize tabindex on the items WE manage: the first managed
    // item gets `0`, the rest `-1`. Items that already carry an
    // explicit `tabindex` attribute when the directive mounts are
    // left alone — they're considered author-controlled (e.g. an
    // input that needs to be reachable via Tab from outside the
    // group, or a focus-trap sentinel) and stay out of the roving
    // group entirely. We tag managed items with `data-rf-managed`
    // so subsequent reapply / navigation can identify them without
    // re-checking the original tabindex.
    const items = this.items();
    const managed = items.filter((el) => !el.hasAttribute('tabindex'));
    for (const el of managed) {
      el.dataset['rfManaged'] = 'true';
    }
    managed.forEach((el, i) => {
      el.setAttribute('tabindex', i === 0 ? '0' : '-1');
    });
  }

  ngOnDestroy(): void {
    // Restore items so the next mount of this template doesn't
    // inherit stale tabindex / data attributes from a recycled DOM.
    // Defensive only — standalone components are usually torn down
    // with their DOM.
    for (const item of this.managedItems()) {
      delete item.dataset['rfManaged'];
      item.removeAttribute('tabindex');
    }
  }

  @HostListener('keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (!this.modifierMatches(event)) return;
    const direction = this.directionFor(event.key);
    if (direction === 0) return;

    // Navigate over managed items only. Author-tabindexed items in
    // the same group don't participate in arrow walking — they were
    // intentionally opted out at init time.
    const managed = this.managedItems();
    if (managed.length === 0) return;

    const currentIndex = this.activeIndex(managed);
    let nextIndex = currentIndex + direction;
    if (nextIndex < 0) nextIndex = this.wrap ? managed.length - 1 : 0;
    if (nextIndex >= managed.length) nextIndex = this.wrap ? 0 : managed.length - 1;
    if (nextIndex === currentIndex) {
      // Clamped at boundary — preventDefault still, so the page
      // doesn't scroll while the user holds the arrow.
      event.preventDefault();
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    this.applyTabindex(managed, nextIndex);
    managed[nextIndex].focus({ preventScroll: false });
  }

  private modifierMatches(event: KeyboardEvent): boolean {
    switch (this.modifier) {
      case 'alt':
        return event.altKey && !event.ctrlKey && !event.metaKey;
      case 'ctrl':
        return event.ctrlKey && !event.altKey && !event.metaKey;
      case 'none':
      default:
        // No-modifier mode: also reject if any modifier is held, so
        // browser shortcuts (Ctrl+Down, Alt+Down for tab nav, etc.)
        // pass through untouched.
        return !event.altKey && !event.ctrlKey && !event.metaKey;
    }
  }

  private directionFor(key: string): -1 | 0 | 1 {
    const v = this.orientation === 'vertical' || this.orientation === 'both';
    const h = this.orientation === 'horizontal' || this.orientation === 'both';
    if (v && key === 'ArrowUp') return -1;
    if (v && key === 'ArrowDown') return 1;
    if (h && key === 'ArrowLeft') return -1;
    if (h && key === 'ArrowRight') return 1;
    return 0;
  }

  private items(): HTMLElement[] {
    const list = this.host.nativeElement.querySelectorAll<HTMLElement>(this.itemsSelector);
    return Array.from(list);
  }

  /**
   * Items the directive currently owns the tabindex of — i.e. those
   * that didn't have an explicit tabindex when the group mounted.
   * Re-queried on every call so dynamically-added rows participate
   * (they get marked managed by the next applyTabindex on a keystroke,
   * since they have no pre-existing tabindex either).
   */
  private managedItems(): HTMLElement[] {
    return this.items().filter((el) => el.dataset['rfManaged'] === 'true');
  }

  private activeIndex(managed: HTMLElement[]): number {
    const focused = document.activeElement as HTMLElement | null;
    if (focused) {
      const i = managed.indexOf(focused);
      if (i >= 0) return i;
    }
    // Fall back to whichever item we marked tabindex="0" — covers the
    // case where the user just Tab-entered the group from outside.
    const tabbable = managed.findIndex((el) => el.getAttribute('tabindex') === '0');
    return Math.max(tabbable, 0);
  }

  private applyTabindex(managed: HTMLElement[], activeIndex: number): void {
    managed.forEach((el, i) => {
      el.setAttribute('tabindex', i === activeIndex ? '0' : '-1');
    });
  }
}
