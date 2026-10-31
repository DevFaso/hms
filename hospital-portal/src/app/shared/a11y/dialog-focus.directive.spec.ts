import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DialogFocusDirective } from './dialog-focus.directive';

@Component({
  standalone: true,
  imports: [DialogFocusDirective],
  template: `
    <button id="opener">open</button>
    @if (open()) {
      <div
        role="dialog"
        aria-modal="true"
        appDialogFocus
        (dialogDismiss)="dismissed = dismissed + 1"
      >
        <input id="first" />
        <button id="last">ok</button>
      </div>
    }
  `,
})
class HostComponent {
  open = signal(false);
  dismissed = 0;
}

describe('DialogFocusDirective (QA D947/D988)', () => {
  function setup() {
    const fixture = TestBed.createComponent(HostComponent);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const opener = fixture.nativeElement.querySelector('#opener') as HTMLButtonElement;
    opener.focus();
    fixture.componentInstance.open.set(true);
    fixture.detectChanges();
    return { fixture, opener };
  }

  it('moves focus into the dialog, keeps Tab inside and dismisses on Escape', () => {
    const { fixture } = setup();
    const dialog = fixture.nativeElement.querySelector('[role="dialog"]') as HTMLElement;
    const first = fixture.nativeElement.querySelector('#first') as HTMLElement;
    const last = fixture.nativeElement.querySelector('#last') as HTMLElement;
    expect(document.activeElement).toBe(first);

    last.focus();
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
    expect(document.activeElement).toBe(first);

    dialog.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true }),
    );
    expect(document.activeElement).toBe(last);

    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(fixture.componentInstance.dismissed).toBe(1);
    fixture.nativeElement.remove();
  });

  it('gives focus back to the opener when the dialog closes', () => {
    const { fixture, opener } = setup();
    fixture.componentInstance.open.set(false);
    fixture.detectChanges();
    expect(document.activeElement).toBe(opener);
    fixture.nativeElement.remove();
  });
});
