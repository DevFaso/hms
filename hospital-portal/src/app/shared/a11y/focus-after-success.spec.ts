import { ChangeDetectionStrategy, Component, ViewChild, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ModalDialogDirective } from './modal-dialog.directive';
import { DialogA11yDirective } from './dialog-a11y.directive';
import { DialogFocusDirective } from './dialog-focus.directive';
import { installFocusRescue, uninstallFocusRescue } from './focus-rescue';

/**
 * QA D1283 / D1284 (round 27; the D1004 / D1260 / D1270 class): after a
 * SUCCESSFUL dialog action the screen reloads its list under a loading flag,
 * so the row button that opened the dialog is destroyed with the table and
 * focus ended on the page component (`APP-NURSE-STATION`, `APP-LAB`). Escape
 * passed because a cancel reloads nothing. This host is that screen, for each
 * of the three dialog directives every dialog of the portal goes through
 * (scripts/lib/focus-after-action.test.mjs holds the portal to them).
 */
type Kind = 'modal' | 'a11y' | 'focus';

interface Row {
  id: string;
  patient: string;
  drug: string;
  canGive: boolean;
}

@Component({
  selector: 'app-test-ward',
  standalone: true,
  imports: [ModalDialogDirective, DialogA11yDirective, DialogFocusDirective],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <h1 id="title">Poste de soins</h1>
    <button id="tab" class="tab active">MAR</button>
    @if (loading()) {
      <p>Chargement…</p>
    }
    @if (!loading() && rows().length > 0) {
      <div [attr.data-focus-return]="hint">
        <table>
          <tbody>
            @for (r of rows(); track r.id) {
              <tr>
                <td>{{ r.patient }}</td>
                <td>{{ r.drug }}</td>
                <td>
                  <a href="#chart" [id]="'chart-' + r.id">Dossier</a>
                  @if (r.canGive) {
                    <button class="give" (click)="open(r.id)">Donner</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    @if (openFor() && kind === 'modal') {
      <div appModalDialog aria-label="Donner" (escaped)="openFor.set(null)">
        <button id="confirm" (click)="confirm()">Confirmer</button>
      </div>
    }
    @if (openFor() && kind === 'a11y') {
      <div appDialogA11y aria-label="Donner" (dialogClose)="openFor.set(null)">
        <button id="confirm" (click)="confirm()">Confirmer</button>
      </div>
    }
    @if (openFor() && kind === 'focus') {
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Donner"
        appDialogFocus
        (dialogDismiss)="openFor.set(null)"
      >
        <button id="confirm" (click)="confirm()">Confirmer</button>
      </div>
    }
  `,
})
class WardHost {
  kind: Kind = 'modal';
  /** Where the table says focus goes when its row is gone (`data-focus-return`), or nothing. */
  hint: string | null = null;
  readonly loading = signal(false);
  readonly rows = signal<Row[]>([]);
  readonly openFor = signal<string | null>(null);

  open(id: string): void {
    this.openFor.set(id);
  }

  /** The success path of the nurse station and the lab: close, then reload under the flag. */
  confirm(): void {
    this.openFor.set(null);
    this.loading.set(true);
  }

  /** The reload answers. */
  loaded(rows: Row[]): void {
    this.rows.set(rows);
    this.loading.set(false);
  }
}

/** The page is a component inside the shell, as in the app (`<app-nurse-station>`). */
@Component({
  selector: 'app-test-shell',
  standalone: true,
  imports: [WardHost],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `<main><app-test-ward /></main>`,
})
class ShellHost {
  @ViewChild(WardHost, { static: true }) ward!: WardHost;
}

const ROWS: Row[] = [
  { id: 'm1', patient: 'Awa Sanou', drug: 'Aspirine', canGive: true },
  { id: 'm2', patient: 'Awa Sanou', drug: 'Morphine', canGive: true },
  { id: 'm3', patient: 'Ibrahim Compaoré', drug: 'Paracétamol', canGive: true },
];

describe('focus after a successful dialog action (QA D1283 / D1284 class)', () => {
  let fixture: ComponentFixture<ShellHost>;
  let host: WardHost;
  let el: HTMLElement;

  /** Lets the focus rescue's mutation observer and the directives' timers run. */
  const settle = async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await new Promise((resolve) => setTimeout(resolve, 0));
  };

  const giveIn = (patient: string, drug: string): HTMLButtonElement => {
    const row = [...el.querySelectorAll('tr')].find(
      (r) =>
        r.cells[0]?.textContent?.trim() === patient && r.cells[1]?.textContent?.trim() === drug,
    )!;
    return row.querySelector<HTMLButtonElement>('button.give')!;
  };

  async function openAndConfirm(kind: Kind): Promise<void> {
    host.kind = kind;
    host.loaded(ROWS);
    fixture.detectChanges();
    const give = giveIn('Awa Sanou', 'Morphine');
    give.focus();
    give.click();
    fixture.detectChanges();
    await settle();
    el.querySelector<HTMLButtonElement>('#confirm')!.click();
    fixture.detectChanges();
    await settle();
  }

  beforeEach(() => {
    installFocusRescue(document);
    fixture = TestBed.createComponent(ShellHost);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    host = fixture.componentInstance.ward;
    el = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('app-test-ward')!;
  });

  afterEach(() => {
    fixture.destroy();
    (fixture.nativeElement as HTMLElement).remove();
    uninstallFocusRescue();
  });

  for (const kind of ['modal', 'a11y', 'focus'] as const) {
    it(`${kind}: while the list reloads focus waits on the page heading, never the page host`, async () => {
      await openAndConfirm(kind);
      expect(document.activeElement).not.toBe(el);
      expect(document.activeElement).not.toBe(document.body);
      expect(document.activeElement).toBe(el.querySelector('#title'));
    });

    it(`${kind}: when the list is back focus returns to the same action of the same row`, async () => {
      await openAndConfirm(kind);
      host.loaded(ROWS);
      fixture.detectChanges();
      await settle();
      expect(document.activeElement).toBe(giveIn('Awa Sanou', 'Morphine'));
    });
  }

  it('the action left its row (the dose was given): the row keeps the focus', async () => {
    await openAndConfirm('modal');
    host.loaded(ROWS.map((r) => (r.id === 'm2' ? { ...r, canGive: false } : r)));
    fixture.detectChanges();
    await settle();
    expect(document.activeElement).toBe(el.querySelector('#chart-m2'));
  });

  it('the row left the list (the order was voided): the place the table named', async () => {
    host.hint = 'app-test-ward .tab.active';
    await openAndConfirm('modal');
    // The named place stands in while the list reloads …
    expect(document.activeElement).toBe(el.querySelector('#tab'));
    host.loaded(ROWS.filter((r) => r.id !== 'm2'));
    fixture.detectChanges();
    await settle();
    expect(document.activeElement).toBe(el.querySelector('#tab'));
  });

  it('a cancel still gives focus straight back to the opener', async () => {
    host.loaded(ROWS);
    fixture.detectChanges();
    const give = giveIn('Ibrahim Compaoré', 'Paracétamol');
    give.focus();
    give.click();
    fixture.detectChanges();
    await settle();
    host.openFor.set(null);
    fixture.detectChanges();
    await settle();
    expect(document.activeElement).toBe(give);
  });

  it('a named stand-in still yields to the twin when the row comes back', async () => {
    host.hint = 'app-test-ward .tab.active';
    await openAndConfirm('a11y');
    expect(document.activeElement).toBe(el.querySelector('#tab'));
    host.loaded(ROWS);
    fixture.detectChanges();
    await settle();
    expect(document.activeElement).toBe(giveIn('Awa Sanou', 'Morphine'));
  });

  it('focus the reader moved on is never taken back when the list returns', async () => {
    await openAndConfirm('modal');
    el.querySelector<HTMLButtonElement>('#tab')!.focus();
    host.loaded(ROWS);
    fixture.detectChanges();
    await settle();
    expect(document.activeElement).toBe(el.querySelector('#tab'));
  });
});
