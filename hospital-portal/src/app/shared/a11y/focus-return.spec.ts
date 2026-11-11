import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';

import { ModalDialogDirective } from './modal-dialog.directive';
import { DialogA11yDirective } from './dialog-a11y.directive';
import { DialogFocusDirective } from './dialog-focus.directive';
import { captureOpener } from './focus-return';

/**
 * QA D1125 / D1004 (round 20): the nursing-intake dialog gave focus back to
 * the page body. Its opener disables itself while the visit is looked up
 * (`[disabled]="encounterLookupInProgress()"`); a disabled button drops focus
 * to the body, so the dialog, opening after the lookup, remembered BODY as its
 * opener. Every opener that disables itself while it loads hit the same wall.
 * The three dialog directives now take their opener from focus-return.ts.
 */
@Component({
  standalone: true,
  imports: [ModalDialogDirective, DialogA11yDirective, DialogFocusDirective],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <button id="opener" [disabled]="busy()" (click)="open()">Admission infirmière</button>
    @if (shown() === 'modal') {
      <div appModalDialog aria-label="Intake" (escaped)="shown.set(null)">
        <button id="inside">OK</button>
      </div>
    }
    @if (shown() === 'a11y') {
      <div appDialogA11y aria-label="Intake" (dialogClose)="shown.set(null)">
        <button id="inside">OK</button>
      </div>
    }
    @if (shown() === 'focus') {
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Intake"
        appDialogFocus
        (dialogDismiss)="shown.set(null)"
      >
        <button id="inside">OK</button>
      </div>
    }
  `,
})
class SelfDisablingOpenerHost {
  readonly busy = signal(false);
  readonly shown = signal<'modal' | 'a11y' | 'focus' | null>(null);
  kind: 'modal' | 'a11y' | 'focus' = 'modal';

  /** The nurse station's lookup: the button disables, the dialog opens later. */
  open(): void {
    this.busy.set(true);
    setTimeout(() => {
      this.busy.set(false);
      this.shown.set(this.kind);
    }, 50);
  }
}

describe('focus-return (QA D1125): an opener that disables itself still gets focus back', () => {
  let fixture: ComponentFixture<SelfDisablingOpenerHost>;

  beforeEach(() => {
    fixture = TestBed.createComponent(SelfDisablingOpenerHost);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  for (const kind of ['modal', 'a11y', 'focus'] as const) {
    it(`${kind}: focus goes back to the disabled-then-enabled opener, not to BODY`, fakeAsync(() => {
      fixture.componentInstance.kind = kind;
      const host = fixture.nativeElement as HTMLElement;
      const opener = host.querySelector<HTMLButtonElement>('#opener')!;
      opener.focus();
      opener.click();
      fixture.detectChanges();
      expect(opener.disabled).withContext('the opener disabled itself').toBeTrue();
      // What the browser's focus fixup does at its next frame: a disabled
      // button cannot keep focus, it falls to the page (BODY in QA's run).
      opener.blur();
      expect(document.activeElement).withContext('focus fell to the page').toBe(document.body);

      tick(50);
      fixture.detectChanges();
      tick(); // appDialogA11y moves focus in on the next turn
      const inside = host.querySelector<HTMLElement>('#inside')!;
      expect(document.activeElement).withContext('focus moved into the dialog').toBe(inside);

      inside.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );
      fixture.detectChanges();
      expect(host.querySelector('#inside')).withContext('Escape closed it').toBeNull();
      expect(document.activeElement).withContext('focus back on the opener').toBe(opener);
    }));
  }

  it('captureOpener prefers the element that has focus', () => {
    const host = fixture.nativeElement as HTMLElement;
    const opener = host.querySelector<HTMLButtonElement>('#opener')!;
    opener.focus();
    expect(captureOpener()).toBe(opener);
  });
});

/**
 * QA D1260 (round 26, D1004 sibling): the login page's "Nom d'utilisateur
 * oublié ?" dialog has a "Réinitialiser le mot de passe" button that closes it
 * and opens the reset dialog in the same click. The reset dialog's opener was
 * that button, removed with the first dialog, so closing the reset dialog left
 * focus on the page root. The reset dialog is written first in the template,
 * as on the login page, so it is created while the first dialog still stands.
 */
@Component({
  standalone: true,
  imports: [ModalDialogDirective],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <a id="forgot-username" href="javascript:void(0)" (click)="shown.set('username')"
      >Nom d'utilisateur oublié ?</a
    >
    @if (shown() === 'password') {
      <div appModalDialog aria-label="Réinitialiser" (escaped)="shown.set(null)">
        <button id="back" (click)="shown.set(null)">Retour à la connexion</button>
      </div>
    }
    @if (shown() === 'username') {
      <div appModalDialog aria-label="Nom d'utilisateur" (escaped)="shown.set(null)">
        <button id="to-reset" (click)="shown.set('password')">Réinitialiser le mot de passe</button>
      </div>
    }
  `,
})
class ChainedHostComponent {
  readonly shown = signal<'username' | 'password' | null>(null);
}

describe('focus return across chained dialogs (QA D1260)', () => {
  let fixture: ComponentFixture<ChainedHostComponent>;
  let host: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ChainedHostComponent] });
    fixture = TestBed.createComponent(ChainedHostComponent);
    host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
    fixture.detectChanges();
  });

  afterEach(() => host.remove());

  function openChain(): void {
    const link = host.querySelector<HTMLAnchorElement>('#forgot-username')!;
    link.focus();
    link.click();
    fixture.detectChanges();
    const toReset = host.querySelector<HTMLButtonElement>('#to-reset')!;
    toReset.focus();
    toReset.click();
    fixture.detectChanges();
    expect(host.querySelector('#to-reset')).toBeNull();
    expect(host.querySelector('#back')).not.toBeNull();
  }

  it('closing the second dialog gives focus back to the link that opened the first', () => {
    openChain();
    host.querySelector<HTMLButtonElement>('#back')!.click();
    fixture.detectChanges();
    expect(document.activeElement?.id).toBe('forgot-username');
  });

  it('Escape on the second dialog does the same', () => {
    openChain();
    host
      .querySelector('#back')!
      .dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );
    fixture.detectChanges();
    expect(document.activeElement?.id).toBe('forgot-username');
  });
});
