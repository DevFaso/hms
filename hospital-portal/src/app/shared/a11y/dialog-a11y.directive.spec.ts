import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';

import { DialogA11yDirective } from './dialog-a11y.directive';

/**
 * QA D976: the billing modals kept focus on the row button behind them,
 * ignored Escape, let Tab reach the page underneath and had no dialog role.
 */
@Component({
  standalone: true,
  imports: [DialogA11yDirective],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <button id="opener" (click)="open.set(true)">Open</button>
    @if (open()) {
      <div class="backdrop">
        <div
          id="box"
          appDialogA11y
          tabindex="-1"
          aria-labelledby="t"
          (dialogClose)="open.set(false)"
          (keydown.escape)="$event.stopPropagation()"
        >
          <h2 id="t">Payment</h2>
          <input id="amount" />
          <button id="cancel">Cancel</button>
          <button id="save">Save</button>
        </div>
      </div>
    }
  `,
})
class HostComponent {
  open = signal(false);
}

describe('DialogA11yDirective (QA D976)', () => {
  let fixture: ComponentFixture<HostComponent>;
  const el = (id: string) => fixture.nativeElement.querySelector('#' + id) as HTMLElement;
  const key = (target: HTMLElement, k: string, shiftKey = false) => {
    const event = new KeyboardEvent('keydown', {
      key: k,
      shiftKey,
      bubbles: true,
      cancelable: true,
    });
    target.dispatchEvent(event);
    return event;
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
  });

  afterEach(() => fixture.nativeElement.remove());

  function openDialog(): void {
    el('opener').focus();
    el('opener').click();
    fixture.detectChanges();
    tick();
  }

  it('is a modal dialog named by its title', fakeAsync(() => {
    openDialog();
    expect(el('box').getAttribute('role')).toBe('dialog');
    expect(el('box').getAttribute('aria-modal')).toBe('true');
    expect(el('box').getAttribute('aria-labelledby')).toBe('t');
  }));

  it('moves focus into the dialog when it opens', fakeAsync(() => {
    openDialog();
    expect(document.activeElement).toBe(el('amount'));
  }));

  it('closes on Escape although the box stops its propagation, and gives focus back', fakeAsync(() => {
    openDialog();
    key(el('amount'), 'Escape');
    fixture.detectChanges();
    tick();
    expect(fixture.componentInstance.open()).toBeFalse();
    expect(document.activeElement).toBe(el('opener'));
  }));

  it('keeps Tab and Shift+Tab inside the dialog', fakeAsync(() => {
    openDialog();
    el('save').focus();
    const forward = key(el('save'), 'Tab');
    expect(forward.defaultPrevented).toBeTrue();
    expect(document.activeElement).toBe(el('amount'));

    const back = key(el('amount'), 'Tab', true);
    expect(back.defaultPrevented).toBeTrue();
    expect(document.activeElement).toBe(el('save'));
  }));

  it('lets Tab move normally between the inner fields', fakeAsync(() => {
    openDialog();
    const middle = key(el('amount'), 'Tab');
    expect(middle.defaultPrevented).toBeFalse();
  }));
});
