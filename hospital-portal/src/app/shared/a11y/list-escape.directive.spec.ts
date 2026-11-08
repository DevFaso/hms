import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { ListEscapeDirective } from './list-escape.directive';
import { ModalDialogDirective } from './modal-dialog.directive';

/**
 * QA D1259 (round 26): Escape in the waitlist's provider field, with its list
 * open, closed the whole dialog (or asked to discard the reason typed).
 */
@Component({
  standalone: true,
  imports: [ModalDialogDirective, ListEscapeDirective],
  template: `
    <div class="modal" appModalDialog aria-label="Liste d'attente" (escaped)="closed = closed + 1">
      <textarea id="reason"></textarea>
      <input
        id="provider"
        [appListEscape]="open()"
        (listEscape)="open.set(false)"
        data-escape-discards
      />
      @if (open()) {
        <ul class="autocomplete-list">
          <li>Dr Issouf</li>
        </ul>
      }
    </div>
  `,
})
class HostComponent {
  readonly open = signal(true);
  closed = 0;
}

describe('ListEscapeDirective (QA D1259)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent, TranslateModule.forRoot()] });
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
    document.body.appendChild(el);
  });

  afterEach(() => el.remove());

  const escape = (target: Element) => {
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    target.dispatchEvent(event);
    fixture.detectChanges();
    return event;
  };

  it('Escape over the open list closes the list and leaves the dialog open', () => {
    const provider = el.querySelector('#provider')!;
    const event = escape(provider);
    expect(fixture.componentInstance.open()).toBeFalse();
    expect(el.querySelector('.autocomplete-list')).toBeNull();
    expect(fixture.componentInstance.closed).toBe(0);
    expect(event.defaultPrevented).toBeTrue();
  });

  it('with typed text elsewhere, Escape over the list does not ask to discard it', () => {
    const reason = el.querySelector<HTMLTextAreaElement>('#reason')!;
    reason.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    reason.value = 'Douleur';
    reason.dispatchEvent(new Event('input', { bubbles: true }));
    escape(el.querySelector('#provider')!);
    expect(fixture.componentInstance.open()).toBeFalse();
    expect(el.querySelector('[data-testid="dialog-discard-ask"]')).toBeNull();
    expect(fixture.componentInstance.closed).toBe(0);
  });

  it('with the list closed, Escape goes on to the dialog as before', () => {
    fixture.componentInstance.open.set(false);
    fixture.detectChanges();
    escape(el.querySelector('#provider')!);
    expect(fixture.componentInstance.closed).toBe(1);
  });
});
