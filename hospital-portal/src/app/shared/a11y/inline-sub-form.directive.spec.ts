import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { FocusOnRenderDirective } from './focus-on-render.directive';
import { installFocusRescue, uninstallFocusRescue } from './focus-rescue';
import { InlineDiscardAskComponent } from './inline-discard-ask.component';
import { InlineSubFormDirective } from './inline-sub-form.directive';
import { ModalDialogDirective } from './modal-dialog.directive';

/**
 * QA D1220 (round 23; the D1191 / D1195 focus sibling): Escape or "Abandonner
 * le texte" in a row editor left the keyboard on an ANCESTOR — the price cell
 * (TD) on /pharmacy/inventory, the invoice's lines TBODY in billing — not on
 * the pencil that opened it. The app-wide focus rescue (installed at start-up,
 * as here) put focus on the surviving cell first, and the editor's own return
 * saw "not the body" and gave up.
 *
 * Both shapes: a page-level row editor (appInlineSubForm + appFocusOnRender's
 * return, the inventory price) and a row editor inside a dialog with a named
 * return (inlineSubFormReturn, the invoice line).
 */
@Component({
  standalone: true,
  imports: [
    TranslateModule,
    InlineSubFormDirective,
    InlineDiscardAskComponent,
    FocusOnRenderDirective,
    ModalDialogDirective,
  ],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <table>
      <tbody>
        @for (row of rows; track row) {
          <tr>
            <td class="price-cell">
              @if (editingPage() === row) {
                <span appInlineSubForm #f="inlineSubForm" (dismissed)="editingPage.set(null)">
                  <input
                    type="number"
                    [attr.data-testid]="'page-input-' + row"
                    appFocusOnRender
                    [focusOnRenderReturn]="'[data-testid=page-edit-' + row + ']'"
                  />
                  <app-inline-discard-ask [form]="f" />
                </span>
              } @else {
                <button [attr.data-testid]="'page-edit-' + row" (click)="editingPage.set(row)">
                  edit
                </button>
              }
            </td>
          </tr>
        }
      </tbody>
    </table>
    <div appModalDialog aria-label="Invoice">
      <table>
        <tbody class="lines">
          @for (row of rows; track row) {
            <tr>
              @if (editingLine() === row) {
                <td>
                  <span
                    appInlineSubForm
                    #g="inlineSubForm"
                    [inlineSubFormReturn]="'[data-testid=line-edit-' + row + ']'"
                    (dismissed)="editingLine.set(null)"
                  >
                    <input [attr.data-testid]="'line-input-' + row" />
                    <app-inline-discard-ask [form]="g" />
                  </span>
                </td>
              } @else {
                <td>
                  <button [attr.data-testid]="'line-edit-' + row" (click)="editingLine.set(row)">
                    edit
                  </button>
                </td>
              }
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
class HostComponent {
  rows = ['a', 'b'];
  editingPage = signal<string | null>(null);
  editingLine = signal<string | null>(null);
}

describe('inline sub-form: focus back to the opener after Escape or Discard (QA D1220)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HTMLElement;
  const settle = async () => {
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
  };

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [HostComponent, TranslateModule.forRoot()] });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.nativeElement as HTMLElement;
    installFocusRescue(document);
    await settle();
  });

  afterEach(() => {
    uninstallFocusRescue();
    fixture.destroy();
  });

  const el = (testId: string) => host.querySelector<HTMLElement>(`[data-testid="${testId}"]`)!;

  async function open(kind: 'page' | 'line', row: string): Promise<HTMLInputElement> {
    const pencil = el(`${kind}-edit-${row}`);
    pencil.focus();
    pencil.click();
    await settle();
    return el(`${kind}-input-${row}`) as HTMLInputElement;
  }

  function escape(): void {
    (document.activeElement as HTMLElement).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
  }

  for (const kind of ['page', 'line'] as const) {
    const where = kind === 'page' ? 'a page row editor (inventory price)' : 'a row editor in a dialog (invoice line)';

    it(`${where}: Escape with nothing typed gives the keyboard back to the pencil`, async () => {
      const input = await open(kind, 'b');
      input.focus();
      escape();
      await settle();
      expect(document.activeElement)
        .withContext(`focus on ${document.activeElement?.tagName}`)
        .toBe(el(`${kind}-edit-b`));
    });

    it(`${where}: typed, Escape, "Discard" gives the keyboard back to the pencil, not the cell or the table body`, async () => {
      const input = await open(kind, 'b');
      input.focus();
      input.value = '31';
      input.dispatchEvent(new Event('input'));
      escape();
      await settle();
      const discard = el('inline-discard');
      discard.focus();
      discard.click();
      await settle();
      expect(document.activeElement)
        .withContext(`focus on ${document.activeElement?.tagName}.${(document.activeElement as HTMLElement)?.className}`)
        .toBe(el(`${kind}-edit-b`));
    });
  }

  it('a focus the reader moved on purpose is never taken back', async () => {
    const input = await open('page', 'a');
    input.focus();
    escape();
    // Before the late return runs, the reader tabs to another control.
    el('line-edit-a').focus();
    await settle();
    expect(document.activeElement).toBe(el('line-edit-a'));
  });
});
