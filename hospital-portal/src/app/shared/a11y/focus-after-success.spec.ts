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

/**
 * QA D1297 / D1298 (round 28; D1283 / D1004 NOT_FIXED again). Round 27 kept
 * "the nearest surviving ancestor, as it is" as the stand-in, so focus rested
 * on an anonymous `div.portal-page`, a bare `tbody`, the flow board's
 * `col-body` or `div.chart-container` (D1297), and — with the billing detail
 * dialog still open — on the page H1 behind it (D1298: the reversal dialog
 * closed in the same pass that re-enabled its opener, so focus() hit a
 * disabled button). This host is the billing shape: a detail dialog, its
 * payments table, a row button disabled while the request runs, and a second
 * dialog opened from that row.
 */
interface Payment {
  id: string;
  date: string;
  method: string;
  status: 'CONFIRMED' | 'REVERSED';
}

@Component({
  selector: 'app-test-billing',
  standalone: true,
  imports: [DialogA11yDirective],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <div class="portal-page">
      <h1 id="page-title">Facturation</h1>
      <button id="view" (click)="detailOpen.set(true)">Voir le détail</button>
      @if (detailOpen()) {
        <div appDialogA11y aria-labelledby="detail-title" (dialogClose)="detailOpen.set(false)">
          <h2 id="detail-title">Facture 132</h2>
          <div class="payments-section">
            <h3 id="payments-title">Paiements</h3>
            <table>
              <tbody>
                @for (p of payments(); track p.id) {
                  <tr>
                    <td>{{ p.date }}</td>
                    <td>{{ p.method }}</td>
                    <td>
                      @if (p.status === 'CONFIRMED') {
                        <button
                          class="reverse"
                          [disabled]="busy() || holdDisabled()"
                          (click)="reversing.set(p.id)"
                        >
                          Contre-passer
                        </button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }
      @if (reversing()) {
        <div appDialogA11y aria-label="Contre-passer" (dialogClose)="reversing.set(null)">
          <button id="confirm-reversal" [disabled]="busy()" (click)="busy.set(true)">
            Confirmer
          </button>
        </div>
      }
    </div>
  `,
})
class BillingHost {
  readonly detailOpen = signal(false);
  readonly busy = signal(false);
  /** The opener stays disabled after the dialog closed (a slower flag). */
  readonly holdDisabled = signal(false);
  readonly reversing = signal<string | null>(null);
  readonly payments = signal<Payment[]>([
    { id: 'p1', date: '18/10', method: 'Espèces', status: 'CONFIRMED' },
    { id: 'p2', date: '19/10', method: 'Mobile', status: 'CONFIRMED' },
  ]);

  /** billing.ts submitReversal's success path: busy cleared and the dialog closed in one pass. */
  answered(): void {
    this.busy.set(false);
    this.reversing.set(null);
  }

  /** The payments reload: the reversed row keeps its place, its button is gone. */
  reloaded(): void {
    this.payments.update((rows) =>
      rows.map((r) => (r.id === 'p1' ? { ...r, status: 'REVERSED' as const } : r)),
    );
  }
}

describe('focus after success: named stand-ins and open parent dialogs (QA D1297 / D1298)', () => {
  let fixture: ComponentFixture<BillingHost>;
  let host: BillingHost;
  let el: HTMLElement;

  const settle = async () => {
    for (let i = 0; i < 3; i++) await new Promise((resolve) => setTimeout(resolve, 0));
  };
  const detail = () => el.querySelector<HTMLElement>('[aria-labelledby="detail-title"]')!;
  const reverseIn = (date: string) =>
    [...el.querySelectorAll('tr')]
      .find((r) => r.cells[0]?.textContent?.trim() === date)
      ?.querySelector<HTMLButtonElement>('button.reverse') ?? null;

  async function openDetailAndReversal(): Promise<void> {
    el.querySelector<HTMLButtonElement>('#view')!.click();
    fixture.detectChanges();
    await settle();
    const reverse = reverseIn('18/10')!;
    reverse.focus();
    reverse.click();
    fixture.detectChanges();
    await settle();
  }

  async function reverseFirstPayment(): Promise<void> {
    await openDetailAndReversal();
    el.querySelector<HTMLButtonElement>('#confirm-reversal')!.click();
    fixture.detectChanges();
    await settle();
    host.answered();
    fixture.detectChanges();
    await settle();
  }

  beforeEach(() => {
    installFocusRescue(document);
    fixture = TestBed.createComponent(BillingHost);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    host = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => {
    fixture.destroy();
    el.remove();
    uninstallFocusRescue();
  });

  it('D1298: the reversal closes in the pass that re-enables its opener: focus goes back to it', async () => {
    await reverseFirstPayment();
    expect(document.activeElement).toBe(reverseIn('18/10'));
    expect(detail().contains(document.activeElement)).toBeTrue();
  });

  it('D1298: the opener left its row on reload: focus stays in the open detail dialog, on its section', async () => {
    await reverseFirstPayment();
    host.reloaded();
    fixture.detectChanges();
    await settle();
    expect(reverseIn('18/10')).toBeNull();
    expect(document.activeElement).toBe(el.querySelector('#payments-title'));
    expect(document.activeElement).not.toBe(el.querySelector('#page-title'));
    expect(detail().contains(document.activeElement)).toBeTrue();
  });

  it('D1298: an opener still disabled: focus waits in the open dialog, then returns to it', async () => {
    await openDetailAndReversal();
    host.holdDisabled.set(true);
    fixture.detectChanges();
    host.answered();
    fixture.detectChanges();
    await settle();
    // Never the page heading behind the dialog that is still open.
    expect(document.activeElement).not.toBe(el.querySelector('#page-title'));
    expect(detail().contains(document.activeElement)).toBeTrue();
    host.holdDisabled.set(false);
    fixture.detectChanges();
    await settle();
    expect(document.activeElement).toBe(reverseIn('18/10'));
  });
});

describe('focus after success: the stand-in announces itself (QA D1297, DOM)', () => {
  let page: HTMLElement;
  const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

  beforeEach(() => {
    page = document.createElement('div');
    document.body.appendChild(page);
    installFocusRescue(document);
  });

  afterEach(() => {
    uninstallFocusRescue();
    page.remove();
  });

  it('a page wrapper (div.portal-page) never stands in: its heading does', async () => {
    page.innerHTML = `<app-my-documents><div class="portal-page" tabindex="-1">
        <h1>Mes documents</h1>
        <table><tbody><tr id="r1"><td>Ordonnance</td><td><button id="del">Supprimer</button></td></tr></tbody></table>
      </div></app-my-documents>`;
    page.querySelector<HTMLButtonElement>('#del')!.focus();
    page.querySelector('#r1')!.remove();
    await settle();
    expect(document.activeElement).toBe(page.querySelector('h1'));
  });

  it('a removed card in a board column: the column title, not the anonymous card list', async () => {
    page.innerHTML = `<main><h1>Accueil</h1><div class="flow-col">
        <div class="col-header"><span role="heading" aria-level="2" id="arr">Arrivé</span></div>
        <div class="col-body">
          <div class="patient-card" role="button" tabindex="0"><div class="card-name">Awa</div></div>
          <button id="lwbs">Clôturer sans consultation</button>
        </div></div></main>`;
    page.querySelector<HTMLButtonElement>('#lwbs')!.focus();
    page.querySelector('.patient-card')!.remove();
    page.querySelector('#lwbs')!.remove();
    await settle();
    expect(document.activeElement).toBe(page.querySelector('#arr'));
  });

  it('a heading inside a list item titles that item, not the list: it is skipped', async () => {
    page.innerHTML = `<main><section><h2 id="sec">Accès</h2><ul>
        <li><h3>Awa</h3><button>Révoquer</button></li>
        <li id="gone"><h3>Ali</h3><button id="rev">Révoquer</button></li>
      </ul></section></main>`;
    page.querySelector<HTMLButtonElement>('#rev')!.focus();
    page.querySelector('#gone')!.remove();
    await settle();
    expect(document.activeElement).toBe(page.querySelector('#sec'));
  });

  it('the row survived, its control did not: the row keeps the focus on its first action', async () => {
    page.innerHTML = `<main><table><tbody><tr><td>Awa</td><td>
        <a href="#x" id="chart">Dossier</a><button id="give">Donner</button>
      </td></tr></tbody></table></main>`;
    page.querySelector<HTMLButtonElement>('#give')!.focus();
    page.querySelector('#give')!.remove();
    await settle();
    expect(document.activeElement).toBe(page.querySelector('#chart'));
  });
});
