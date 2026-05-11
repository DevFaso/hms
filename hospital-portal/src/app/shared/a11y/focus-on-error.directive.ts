import { Directive, ElementRef, HostListener, inject } from '@angular/core';

/**
 * v1.0 / Accessibility / Keyboard navigation pass (roadmap row 11).
 *
 * Attaches to a `<form>` and, after every submit, moves keyboard focus to
 * the first invalid form control inside it. Pairs with `aria-invalid` /
 * `aria-describedby` so screen-reader users hear the error message
 * associated with the field they just landed on.
 *
 * Selector intentionally `form[appFocusOnError]` so it only applies to
 * forms that opt in — template-driven, reactive, or hybrid. The directive
 * does not depend on `FormGroup`; it walks the DOM, so it works with
 * Angular's `[(ngModel)]` template-driven inputs (which is what most HMS
 * forms still use) as well as `[formControl]`-driven reactive forms.
 *
 * Standalone (no NgModule). Import directly into the host component's
 * `imports` array.
 *
 * Usage:
 *   <form (ngSubmit)="save()" appFocusOnError>
 *     <input id="firstName" name="firstName" [(ngModel)]="data.firstName" required />
 *     ...
 *   </form>
 *
 * Per docs/ui/accessibility.md §4.
 */
@Directive({
  selector: 'form[appFocusOnError]',
  standalone: true,
})
export class FocusOnErrorDirective {
  private readonly host = inject(ElementRef<HTMLFormElement>);

  /**
   * Selector matching every focusable form control we care about. We
   * deliberately exclude buttons (they're never "invalid") and elements
   * with `tabindex="-1"` (programmatic-only targets like the password-
   * visibility toggle).
   */
  private static readonly CONTROL_SELECTOR = [
    'input:not([type="hidden"]):not([type="submit"]):not([type="button"]):not([tabindex="-1"])',
    'select:not([tabindex="-1"])',
    'textarea:not([tabindex="-1"])',
  ].join(',');

  /**
   * Runs after Angular's submit handlers have had a chance to mark
   * controls as invalid. `ngSubmit` fires before `submit`, but we use
   * `submit` here because Angular re-evaluates validity synchronously
   * inside `ngSubmit` so by the time the bubble reaches the form's
   * native `submit`, `aria-invalid` / the form-control `.ng-invalid`
   * class are settled.
   *
   * `$event.preventDefault()` is NOT called — host components decide
   * whether to submit; we only re-focus on whatever invalid state
   * remains. If the submit was valid (no invalid controls), this is a
   * no-op.
   */
  @HostListener('submit')
  protected onSubmit(): void {
    const form = this.host.nativeElement;
    // Defer to next microtask — gives Angular's validators a tick to
    // populate the `.ng-invalid` class / `aria-invalid` attribute on
    // template-driven forms where validation runs synchronously inside
    // ngSubmit.
    queueMicrotask(() => {
      const target = this.findFirstInvalid(form);
      if (!target) return;
      // `preventScroll: true` on focus() — the subsequent
      // scrollIntoView is responsible for the scroll, so we don`t
      // want focus() to also scroll (causes a visible double-scroll
      // on long forms). Per Copilot review on PR #288.
      target.focus({ preventScroll: true });
      // Honor `prefers-reduced-motion: reduce` so users sensitive to
      // motion don`t get a smooth scroll on every form-validation
      // failure. `auto` is an instant jump which is WCAG 2.3.3 -safe.
      // The matchMedia call is guarded for the SSR / Node test path
      // where `window` is undefined.
      const prefersReducedMotion =
        typeof window !== 'undefined' &&
        window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;
      target.scrollIntoView({
        block: 'center',
        behavior: prefersReducedMotion ? 'auto' : 'smooth',
      });
    });
  }

  private findFirstInvalid(form: HTMLFormElement): HTMLElement | null {
    const candidates = form.querySelectorAll<HTMLElement>(FocusOnErrorDirective.CONTROL_SELECTOR);
    for (const el of Array.from(candidates)) {
      if (this.isInvalid(el)) {
        return el;
      }
    }
    return null;
  }

  private isInvalid(el: HTMLElement): boolean {
    // Angular adds `.ng-invalid` to invalid form controls (and to the
    // form itself). We also honor the native `:invalid` pseudo-class so
    // the directive works on plain HTML5 forms during development.
    if (el.classList.contains('ng-invalid') && el.classList.contains('ng-touched')) {
      return true;
    }
    if (
      el instanceof HTMLInputElement ||
      el instanceof HTMLSelectElement ||
      el instanceof HTMLTextAreaElement
    ) {
      return !el.checkValidity();
    }
    return false;
  }
}
