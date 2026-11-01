import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { DialogA11yDirective } from './dialog-a11y.directive';
import { DialogFocusDirective } from './dialog-focus.directive';
import { ModalDialogDirective } from './modal-dialog.directive';

/**
 * QA D1131 / D1144 / D1190 / D1195 / D1208 (NOT_FIXED rounds 19–22): Escape
 * in a dialog threw away the typed text — the bed board's precaution reason,
 * the invoice's add-line and adjustment forms, the specimen reject reason.
 * The rule lives in the dialog directives, so every dialog has it: these
 * drive each directive and each way a dialog closes on Escape.
 */
@Component({
  standalone: true,
  imports: [ModalDialogDirective, DialogFocusDirective, DialogA11yDirective, TranslateModule],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <button id="opener" type="button">Open</button>
    <div id="page" (keydown.escape)="pageEscapes = pageEscapes + 1">
      @if (modal()) {
        <div class="modal-backdrop" (keydown.escape)="modal.set(false)">
          <div id="modal" appModalDialog aria-label="Precautions" (escaped)="modal.set(false)">
            <input id="modal-reason" type="text" [value]="prefill" />
            <input id="modal-search" type="search" />
            <input id="modal-picker" type="text" data-escape-discards />
            <button id="modal-cancel" type="button" (click)="modal.set(false)">Cancel</button>
          </div>
        </div>
      }
      @if (own()) {
        <div
          id="own"
          appModalDialog
          role="dialog"
          aria-label="Reject"
          tabindex="-1"
          (keydown.escape)="own.set(false); $event.stopPropagation()"
        >
          <textarea id="own-reason"></textarea>
        </div>
      }
      @if (focusDlg()) {
        <div id="focus" appDialogFocus role="dialog" aria-modal="true" aria-label="F" (dialogDismiss)="focusDlg.set(false)">
          <textarea id="focus-reason"></textarea>
        </div>
      }
      @if (a11y()) {
        <div id="a11y" appDialogA11y aria-label="A" (dialogClose)="a11y.set(false)">
          <textarea id="a11y-reason"></textarea>
        </div>
      }
    </div>
  `,
})
class HostComponent {
  modal = signal(false);
  own = signal(false);
  focusDlg = signal(false);
  a11y = signal(false);
  prefill = '';
  pageEscapes = 0;
}

describe('Escape keeps typed text (every dialog directive)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent, TranslateModule.forRoot()] });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    document.body.appendChild(el);
    fixture.detectChanges();
  });

  afterEach(() => el.remove());

  function q<T extends HTMLElement>(sel: string): T {
    return el.querySelector<T>(sel)!;
  }

  function type(field: HTMLInputElement | HTMLTextAreaElement, text: string): void {
    field.focus();
    field.value = text;
    field.dispatchEvent(new Event('input', { bubbles: true }));
  }

  function escape(target: Element): KeyboardEvent {
    const ev = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    target.dispatchEvent(ev);
    fixture.detectChanges();
    return ev;
  }

  it('closes on Escape as before when nothing was typed', () => {
    host.modal.set(true);
    fixture.detectChanges();
    escape(q('#modal-reason'));
    expect(host.modal()).toBeFalse();
    expect(q('[data-testid="dialog-discard-ask"]')).toBeNull();
  });

  it('asks instead of closing when Escape is pressed over typed text, and keeps it', () => {
    host.modal.set(true);
    fixture.detectChanges();
    const reason = q<HTMLInputElement>('#modal-reason');
    type(reason, 'Tuberculose suspectée');
    escape(reason);
    expect(host.modal()).withContext('the dialog stays open').toBeTrue();
    expect(host.pageEscapes).withContext('the key never reaches the page').toBe(0);
    const ask = q('[data-testid="dialog-discard-ask"]');
    expect(ask).not.toBeNull();
    expect(ask.getAttribute('role')).toBe('alert');
    expect(document.activeElement).toBe(q('[data-testid="dialog-keep"]'));
    q<HTMLButtonElement>('[data-testid="dialog-keep"]').click();
    fixture.detectChanges();
    expect(q('[data-testid="dialog-discard-ask"]')).toBeNull();
    expect(document.activeElement).toBe(reason);
    expect(reason.value).toBe('Tuberculose suspectée');
  });

  it('Escape on the question keeps the text; Discard closes the dialog as Escape always did', () => {
    host.modal.set(true);
    fixture.detectChanges();
    const reason = q<HTMLInputElement>('#modal-reason');
    type(reason, 'x');
    escape(reason);
    escape(q('[data-testid="dialog-keep"]'));
    expect(host.modal()).toBeTrue();
    expect(q('[data-testid="dialog-discard-ask"]')).toBeNull();
    escape(reason);
    q<HTMLButtonElement>('[data-testid="dialog-discard"]').click();
    fixture.detectChanges();
    expect(host.modal()).toBeFalse();
  });

  it('a value the form put there, or a search query, is not typed text', () => {
    host.prefill = 'Prérempli';
    host.modal.set(true);
    fixture.detectChanges();
    const search = q<HTMLInputElement>('#modal-search');
    type(search, 'Kaboré');
    const picker = q<HTMLInputElement>('#modal-picker');
    type(picker, 'Ouédraogo');
    escape(q('#modal-reason'));
    expect(host.modal()).toBeFalse();
  });

  it('typing then clearing the field closes without asking', () => {
    host.modal.set(true);
    fixture.detectChanges();
    const reason = q<HTMLInputElement>('#modal-reason');
    type(reason, 'abc');
    type(reason, '   ');
    escape(reason);
    expect(host.modal()).toBeFalse();
  });

  it("stops a dialog's own (keydown.escape) handler too (the lab reject shape, QA D1208)", () => {
    host.own.set(true);
    fixture.detectChanges();
    const reason = q<HTMLTextAreaElement>('#own-reason');
    type(reason, 'Hémolysé');
    escape(reason);
    expect(host.own()).toBeTrue();
    q<HTMLButtonElement>('[data-testid="dialog-discard"]').click();
    fixture.detectChanges();
    expect(host.own()).toBeFalse();
  });

  it('appDialogFocus and appDialogA11y dialogs ask too', () => {
    host.focusDlg.set(true);
    fixture.detectChanges();
    type(q<HTMLTextAreaElement>('#focus-reason'), 'a');
    escape(q('#focus-reason'));
    expect(host.focusDlg()).toBeTrue();
    expect(q('#focus [data-testid="dialog-discard-ask"]')).not.toBeNull();

    host.a11y.set(true);
    fixture.detectChanges();
    type(q<HTMLTextAreaElement>('#a11y-reason'), 'b');
    escape(q('#a11y-reason'));
    expect(host.a11y()).toBeTrue();
    expect(q('#a11y [data-testid="dialog-discard-ask"]')).not.toBeNull();
  });
});
