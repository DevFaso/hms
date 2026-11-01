import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { ReasonDialogComponent } from '../reason-dialog/reason-dialog.component';
import { mouseClick } from '../../testing/mouse-click';
import { DialogA11yDirective } from './dialog-a11y.directive';
import { DialogFocusDirective } from './dialog-focus.directive';
import { ModalDialogDirective } from './modal-dialog.directive';
import { openLayerCount } from './overlay-stack';

/**
 * QA D1209 / D1201 (round 23): every shape of "a dialog opened from inside a
 * dialog" the portal has (lib/nested-dialog-scan.mjs lists the pairs), each
 * driven with the mouse: the child's confirm must take the click while the
 * parent stays open. The parent's stylesheet sets its own z-index, as the
 * screens do (the bed board's `.modal` is 1001, above the shared backdrop's
 * 1000).
 */
@Component({
  standalone: true,
  imports: [
    TranslateModule,
    ModalDialogDirective,
    DialogA11yDirective,
    DialogFocusDirective,
    ReasonDialogComponent,
    ConfirmDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [
    `
      .sib-backdrop {
        position: fixed;
        inset: 0;
        z-index: 1000;
        background: rgba(0, 0, 0, 0.4);
      }
      .sib-modal {
        position: fixed;
        top: 10px;
        left: 10px;
        right: 10px;
        bottom: 10px;
        z-index: 1001;
        background: #fff;
      }
      .side-overlay {
        position: fixed;
        inset: 0;
        z-index: 1500;
      }
      .side-panel {
        position: absolute;
        inset: 0;
        background: #fff;
      }
      .inner-cover {
        position: fixed;
        inset: 0;
        z-index: 5;
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .inner-box {
        background: #fff;
        padding: 1rem;
      }
    `,
  ],
  template: `
    <button id="opener">Open</button>
    @if (parent() === 'modal') {
      <div class="sib-backdrop" tabindex="-1"></div>
      <div class="sib-modal" appModalDialog aria-label="Parent" (escaped)="parent.set(null)">
        <button id="parent-btn" (click)="child.set(childKind)">Open child</button>
        <input id="parent-field" />
      </div>
    }
    @if (parent() === 'panel') {
      <div class="side-overlay">
        <div class="side-panel" appDialogA11y aria-label="Panel" (dialogClose)="parent.set(null)">
          <button id="parent-btn" (click)="child.set(childKind)">Open child</button>
          <input id="parent-field" />
        </div>
      </div>
    }
    @if (parent() === 'focus') {
      <div class="sib-backdrop" tabindex="-1"></div>
      <div
        class="sib-modal"
        appDialogFocus
        role="dialog"
        aria-modal="true"
        aria-label="Parent"
        (dialogDismiss)="parent.set(null)"
      >
        <button id="parent-btn" (click)="child.set(childKind)">Open child</button>
        <input id="parent-field" />
        @if (child() === 'inner') {
          <div class="inner-cover">
            <div class="inner-box" appModalDialog aria-label="Inner" (escaped)="child.set(null)">
              <button id="child-confirm" (click)="confirmed = confirmed + 1">OK</button>
            </div>
          </div>
        }
      </div>
    }
    @if (child() === 'reason') {
      <app-reason-dialog
        titleKey="T"
        (confirmed)="confirmed = confirmed + 1"
        (cancelled)="child.set(null)"
      />
    }
    @if (child() === 'confirm') {
      <app-confirm-dialog
        titleKey="T"
        (confirmed)="confirmed = confirmed + 1"
        (cancelled)="child.set(null)"
      />
    }
  `,
})
class HostComponent {
  parent = signal<'modal' | 'panel' | 'focus' | null>(null);
  child = signal<'reason' | 'confirm' | 'inner' | null>(null);
  childKind: 'reason' | 'confirm' | 'inner' = 'reason';
  confirmed = 0;
}

describe('overlay stack: a dialog opened from inside a dialog (QA D1209)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent, TranslateModule.forRoot()] });
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => {
    fixture.destroy();
    expect(openLayerCount()).withContext('every layer closed with its dialog').toBe(0);
  });

  function open(parent: 'modal' | 'panel' | 'focus', child: 'reason' | 'confirm' | 'inner'): void {
    fixture.componentInstance.parent.set(parent);
    fixture.componentInstance.childKind = child;
    fixture.detectChanges();
    mouseClick(host.querySelector<HTMLElement>('#parent-btn')!);
    fixture.detectChanges();
  }

  function typeReason(text: string): void {
    const field = host.querySelector<HTMLTextAreaElement>('[data-testid="reason-dialog-reason"]')!;
    mouseClick(field);
    field.value = text;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  for (const parent of ['modal', 'panel', 'focus'] as const) {
    it(`the shared reason dialog over a ${parent} parent takes the mouse and the keyboard`, () => {
      open(parent, 'reason');
      const dialog = host.querySelector<HTMLElement>('[data-testid="reason-dialog"]')!;
      expect(dialog.contains(document.activeElement)).withContext('focus moved in').toBeTrue();
      typeReason('Because');
      mouseClick(host.querySelector<HTMLElement>('[data-testid="reason-dialog-confirm"]')!);
      expect(fixture.componentInstance.confirmed).toBe(1);
      expect(fixture.componentInstance.parent()).withContext('the parent stays open').toBe(parent);
    });

    it(`the shared confirm dialog over a ${parent} parent takes the mouse`, () => {
      open(parent, 'confirm');
      mouseClick(host.querySelector<HTMLElement>('[data-testid="confirm-dialog-confirm"]')!);
      expect(fixture.componentInstance.confirmed).toBe(1);
    });

    it(`Tab inside a dialog over a ${parent} parent stays in the child`, () => {
      open(parent, 'confirm');
      const child = host.querySelector<HTMLElement>('[data-testid="confirm-dialog"]')!;
      const cancel = host.querySelector<HTMLElement>('[data-testid="confirm-dialog-cancel"]')!;
      cancel.focus();
      const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
      cancel.dispatchEvent(tab);
      // The browser would move focus to the next control; nothing must have
      // pulled it back into the parent underneath.
      expect(child.contains(document.activeElement))
        .withContext('the parent underneath did not take focus back')
        .toBeTrue();
    });

    it(`Escape in a dialog over a ${parent} parent closes the child only`, () => {
      open(parent, 'confirm');
      (document.activeElement as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );
      fixture.detectChanges();
      expect(fixture.componentInstance.child()).toBeNull();
      expect(fixture.componentInstance.parent()).toBe(parent);
    });
  }

  it('a dialog written INSIDE its parent (its cover a low z-index) still takes the mouse', () => {
    open('focus', 'inner');
    mouseClick(host.querySelector<HTMLElement>('#child-confirm')!);
    expect(fixture.componentInstance.confirmed).toBe(1);
  });

  it('a dialog opened alone keeps the z-index its stylesheet gives it', () => {
    fixture.componentInstance.child.set('reason');
    fixture.detectChanges();
    const cover = host.querySelector<HTMLElement>('app-reason-dialog .modal-backdrop')!;
    expect(cover.style.zIndex).toBe('');
  });
});
