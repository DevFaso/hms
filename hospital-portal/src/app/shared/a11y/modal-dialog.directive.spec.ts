import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ModalDialogDirective } from './modal-dialog.directive';

/**
 * QA D1004: hand-built maternity dialogs left focus on the button that opened
 * them, so Escape (bound on the dialog) never reached them, and nothing kept
 * Tab inside. The shared directive is what every hand-built dialog now uses.
 */
@Component({
  standalone: true,
  imports: [ModalDialogDirective],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <button id="opener" (click)="open.set(true)">Open</button>
    <div id="page" (keydown.escape)="pageEscapes = pageEscapes + 1">
      @if (open()) {
        <div class="modal-backdrop">
          <div id="box" appModalDialog aria-labelledby="t" (escaped)="close()">
            <h2 id="t">Form</h2>
            <input id="first" />
            <button id="confirm" (click)="confirm.set(true)">Delete…</button>
            <button id="last">Cancel</button>
            @if (confirm()) {
              <div id="inner" appModalDialog aria-labelledby="t2" (escaped)="confirm.set(false)">
                <h3 id="t2">Sure?</h3>
                <button id="yes">Yes</button>
              </div>
            }
          </div>
        </div>
      }
      @if (viewOnly()) {
        <div id="view" appModalDialog aria-labelledby="t3" (escaped)="viewOnly.set(false)">
          <h2 id="t3">Read only</h2>
          <p>Nothing to focus here.</p>
        </div>
      }
      @if (unbound()) {
        <div id="unbound" appModalDialog aria-labelledby="t4" (keydown.escape)="unbound.set(false)">
          <h2 id="t4">Own handler</h2>
          <button id="unbound-btn">OK</button>
        </div>
      }
      @if (auto()) {
        <div id="auto" appModalDialog aria-labelledby="t5" (escaped)="auto.set(false)">
          <h2 id="t5">Autofocus</h2>
          <button id="auto-x">x</button>
          <input id="auto-field" autofocus />
        </div>
      }
    </div>
  `,
})
class HostComponent {
  open = signal(false);
  confirm = signal(false);
  viewOnly = signal(false);
  unbound = signal(false);
  auto = signal(false);
  busy = false;
  pageEscapes = 0;
  close(): void {
    // The same guard a Cancel button would respect.
    if (this.busy) return;
    this.open.set(false);
  }
}

describe('ModalDialogDirective (QA D1004)', () => {
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
    fixture.detectChanges();
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
  }

  it('is a modal dialog named by its title', () => {
    openDialog();
    expect(el('box').getAttribute('role')).toBe('dialog');
    expect(el('box').getAttribute('aria-modal')).toBe('true');
    expect(el('box').getAttribute('aria-labelledby')).toBe('t');
  });

  it('moves focus into the dialog when it opens', () => {
    openDialog();
    expect(document.activeElement).toBe(el('first'));
  });

  it('prefers an [autofocus] control', () => {
    fixture.componentInstance.auto.set(true);
    fixture.detectChanges();
    expect(document.activeElement).toBe(el('auto-field'));
  });

  it('focuses the box itself when it has nothing focusable', () => {
    el('opener').focus();
    fixture.componentInstance.viewOnly.set(true);
    fixture.detectChanges();
    expect(document.activeElement).toBe(el('view'));
    expect(el('view').getAttribute('tabindex')).toBe('-1');
    key(el('view'), 'Escape');
    expect(fixture.componentInstance.viewOnly()).toBeFalse();
    expect(document.activeElement).toBe(el('opener'));
  });

  it('closes on Escape through the bound close, and gives focus back to the opener', () => {
    openDialog();
    const event = key(el('first'), 'Escape');
    expect(event.defaultPrevented).toBeTrue();
    expect(fixture.componentInstance.open()).toBeFalse();
    expect(document.activeElement).toBe(el('opener'));
  });

  it('stops a handled Escape so a page-level listener does not fire too', () => {
    openDialog();
    key(el('first'), 'Escape');
    expect(fixture.componentInstance.pageEscapes).toBe(0);
  });

  it('respects the close guard: a busy dialog stays open on Escape', () => {
    openDialog();
    fixture.componentInstance.busy = true;
    key(el('first'), 'Escape');
    expect(fixture.componentInstance.open()).toBeTrue();
  });

  it('keeps Tab and Shift+Tab inside the dialog', () => {
    openDialog();
    el('last').focus();
    const forward = key(el('last'), 'Tab');
    expect(forward.defaultPrevented).toBeTrue();
    expect(document.activeElement).toBe(el('first'));

    const back = key(el('first'), 'Tab', true);
    expect(back.defaultPrevented).toBeTrue();
    expect(document.activeElement).toBe(el('last'));
  });

  it('lets Tab move normally between the inner fields', () => {
    openDialog();
    const middle = key(el('first'), 'Tab');
    expect(middle.defaultPrevented).toBeFalse();
  });

  it('closes only the innermost of two stacked dialogs on Escape, then focus goes back inside the outer one', () => {
    openDialog();
    el('confirm').focus();
    el('confirm').click();
    fixture.detectChanges();
    expect(document.activeElement).toBe(el('yes'));

    key(el('yes'), 'Escape');
    expect(fixture.componentInstance.confirm()).toBeFalse();
    expect(fixture.componentInstance.open()).toBeTrue();
    expect(document.activeElement).toBe(el('confirm'));

    key(el('confirm'), 'Escape');
    expect(fixture.componentInstance.open()).toBeFalse();
  });

  it("does not stop propagation when (escaped) is unbound, so the box's own (keydown.escape) still closes it", () => {
    el('opener').focus();
    fixture.componentInstance.unbound.set(true);
    fixture.detectChanges();
    expect(document.activeElement).toBe(el('unbound-btn'));
    key(el('unbound-btn'), 'Escape');
    expect(fixture.componentInstance.unbound()).toBeFalse();
    expect(fixture.componentInstance.pageEscapes).toBe(1);
  });

  it('closes on Escape and brings Tab back in when focus has left the box', () => {
    openDialog();
    (document.activeElement as HTMLElement).blur();
    const tab = key(document.body, 'Tab');
    expect(tab.defaultPrevented).toBeTrue();
    expect(document.activeElement).toBe(el('first'));
    (document.activeElement as HTMLElement).blur();
    key(document.body, 'Escape');
    expect(fixture.componentInstance.open()).toBeFalse();
  });
});
