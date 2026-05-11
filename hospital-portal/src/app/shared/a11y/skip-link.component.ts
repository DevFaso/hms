import { ChangeDetectionStrategy, Component, Input } from '@angular/core';

/**
 * v1.0 / Accessibility / Keyboard navigation pass (roadmap row 11).
 *
 * "Skip to main content" link — the first focusable element in the shell.
 * Visually hidden until it receives keyboard focus, at which point it
 * appears at the top-left of the viewport. Activating it (Enter) jumps
 * focus to the element identified by `target` (default `#main-content`),
 * letting keyboard / screen-reader users bypass the sidebar nav and topbar.
 *
 * The target element MUST have `tabindex="-1"` so it can receive focus
 * programmatically without becoming part of the normal tab order. See
 * docs/ui/accessibility.md §4 for the focus-management contract.
 *
 * Implemented as a standalone component with no third-party deps so it
 * can be imported by the shell without pulling in CommonModule.
 */
@Component({
  selector: 'app-skip-link',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './skip-link.component.html',
  styleUrl: './skip-link.component.scss',
})
export class SkipLinkComponent {
  /**
   * CSS selector for the element focus jumps to when the link is activated.
   * Defaults to `#main-content` because that's the id the shell puts on its
   * `<main>` element; override only if a route renders its own outlet.
   */
  @Input() target = '#main-content';

  /** Label shown when the link is focused. Translation key by convention. */
  @Input() label = 'A11Y.SKIP_TO_MAIN';

  /**
   * Bound to both `(click)` and `(keydown.enter)` in the template.
   * The parameter is typed as the base `Event` because Angular's AOT
   * template type-checker widens `$event` from `(keydown.enter)` to
   * `Event`, not `KeyboardEvent` — the narrower `MouseEvent |
   * KeyboardEvent` signature broke the production build on PR #297's
   * first CI run (TS2345). We only call `preventDefault()` so the
   * wider type is functionally fine.
   */
  protected onActivate(event: Event): void {
    event.preventDefault();
    const el = document.querySelector<HTMLElement>(this.target);
    if (!el) {
      // Targeting a missing id is a bug — surface it loudly in dev. We
      // do not block keyboard users from re-tabbing into the page; they
      // just stay where they are.
      console.warn(`[SkipLinkComponent] target "${this.target}" not found`);
      return;
    }
    // Native focus(). The target should already carry tabindex="-1" so
    // focus lands without polluting the tab order on subsequent Tabs.
    el.focus({ preventScroll: false });
  }
}
