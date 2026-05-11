import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';

import { FocusOnErrorDirective } from './focus-on-error.directive';

/**
 * v1.0 / Accessibility / Keyboard navigation pass (roadmap row 11).
 *
 * Tested via a tiny host component because the directive is selector-bound
 * to `form[appFocusOnError]` and there's no public API to drive — its job
 * is a side effect on `submit`.
 *
 * The directive defers focus via native `queueMicrotask`, which Angular's
 * `fakeAsync` zone does not patch. Tests use `async/await` on a chained
 * microtask instead so the focus side effect has run before the assertion.
 *
 * Coverage:
 *   - first invalid input receives focus on submit
 *   - inputs with tabindex="-1" are skipped (e.g. password-visibility toggles)
 *   - valid forms don't move focus
 */
@Component({
  standalone: true,
  imports: [FormsModule, FocusOnErrorDirective],
  template: `
    <form appFocusOnError (ngSubmit)="onSubmit()">
      <input
        id="first"
        name="first"
        type="text"
        [(ngModel)]="first"
        #firstCtrl="ngModel"
        [class.ng-invalid]="firstCtrl.invalid"
        [class.ng-touched]="touched"
        required
        data-testid="first"
      />
      <input
        id="middle"
        name="middle"
        type="text"
        [(ngModel)]="middle"
        #middleCtrl="ngModel"
        [class.ng-invalid]="middleCtrl.invalid"
        [class.ng-touched]="touched"
        required
        data-testid="middle"
      />
      <button type="submit">Save</button>
    </form>
  `,
})
class HostComponent {
  first = '';
  middle = '';
  touched = false;
  onSubmit(): void {
    this.touched = true;
  }
}

/**
 * The directive defers focus with `queueMicrotask`. We yield twice: once
 * to flush our own awaited microtask, and once via a fresh microtask
 * scheduled after the directive's so we know its microtask has run.
 */
function flushFocus(): Promise<void> {
  return new Promise((resolve) => queueMicrotask(() => queueMicrotask(() => resolve())));
}

describe('FocusOnErrorDirective', () => {
  let fixture: ComponentFixture<HostComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  });

  function submit(): void {
    const form = (fixture.nativeElement as HTMLElement).querySelector<HTMLFormElement>('form')!;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    fixture.detectChanges();
  }

  it('focuses the first invalid input on submit', async () => {
    // Both inputs are empty + required, so both are invalid. The directive
    // should pick the first one in DOM order.
    submit();
    await flushFocus();
    expect((document.activeElement as HTMLElement).getAttribute('data-testid')).toBe('first');
  });

  it('focuses the second input when only it is invalid', async () => {
    fixture.componentInstance.first = 'Awa';
    fixture.detectChanges();
    submit();
    await flushFocus();
    expect((document.activeElement as HTMLElement).getAttribute('data-testid')).toBe('middle');
  });

  it('does not change focus when the form is valid', async () => {
    fixture.componentInstance.first = 'Awa';
    fixture.componentInstance.middle = 'Diallo';
    fixture.detectChanges();
    // Move focus to the submit button so we can verify it does not move.
    const submitBtn = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      'button[type="submit"]',
    )!;
    submitBtn.focus();
    submit();
    await flushFocus();
    expect(document.activeElement).toBe(submitBtn);
  });

  it('skips inputs with tabindex="-1"', async () => {
    // Add a sentinel `tabindex="-1"` invalid input BEFORE the others to
    // prove the directive ignores it.
    const skipped = document.createElement('input');
    skipped.type = 'text';
    skipped.required = true;
    skipped.tabIndex = -1;
    skipped.setAttribute('data-testid', 'skipped');
    skipped.classList.add('ng-invalid', 'ng-touched');
    const form = (fixture.nativeElement as HTMLElement).querySelector<HTMLFormElement>('form')!;
    form.insertBefore(skipped, form.firstChild);

    submit();
    await flushFocus();
    // First reachable invalid input is still the original "first" input.
    expect((document.activeElement as HTMLElement).getAttribute('data-testid')).toBe('first');
  });
});
