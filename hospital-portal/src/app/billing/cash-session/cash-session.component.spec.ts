import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { CashSessionComponent } from './cash-session.component';
import { CashSupervisorComponent } from './cash-supervisor.component';
import {
  CashSession,
  CashSessionService,
  RemovedInvoiceLine,
} from '../../services/cash-session.service';
import { ToastService } from '../../core/toast.service';
import { provideRouter } from '@angular/router';

/**
 * Billing-money plan PR 10 (QA r65 D2325, D2714, D2326): the cashier's counted close and the supervisor's view.
 * Plan acceptance: 1 000 cash and 850 Orange Money in, a 500 F refund out: expected cash 500, Orange 850.
 */
function session(overrides: Partial<CashSession> = {}): CashSession {
  return {
    id: 's1',
    till: 'BILLING',
    cashierId: 'u1',
    cashierName: 'Test Caissier 3',
    businessDate: '2026-11-20',
    status: 'OPEN',
    closeRound: 0,
    mine: true,
    methods: [
      {
        method: 'CASH',
        operator: null,
        inCount: 1,
        inAmount: 1000,
        outCount: 1,
        outAmount: 500,
        expected: 500,
      },
      {
        method: 'MOBILE_MONEY',
        operator: 'ORANGE_MONEY',
        inCount: 1,
        inAmount: 850,
        outCount: 0,
        outAmount: 0,
        expected: 850,
      },
    ],
    totalIn: 1850,
    totalOut: 500,
    totalExpected: 1350,
    cashToCount: 500,
    totalDifference: null,
    counts: [],
    movements: [],
    ...overrides,
  };
}

describe('CashSessionComponent (billing plan PR 10)', () => {
  function render(rows: CashSession[], confirmed = true) {
    const api = jasmine.createSpyObj<CashSessionService>('CashSessionService', [
      'mine',
      'close',
      'sessionPdf',
    ]);
    api.mine.and.returnValue(of(rows));
    api.close.and.callFake((id, counts) =>
      of(
        session({
          status: 'CLOSED',
          closeRound: 1,
          totalDifference: 0,
          counts: counts.map((c) => ({
            closeRound: 1,
            method: c.method,
            operator: c.operator,
            expected: 0,
            counted: c.counted ?? 0,
            difference: 0,
            explanation: c.explanation ?? null,
          })),
        }),
      ),
    );
    const toast = jasmine.createSpyObj<ToastService>('ToastService', [
      'success',
      'error',
      'info',
      'warning',
    ]);
    const confirm = jasmine.createSpyObj<ConfirmService>('ConfirmService', ['ask']);
    confirm.ask.and.resolveTo(confirmed);
    TestBed.configureTestingModule({
      imports: [CashSessionComponent, TranslateModule.forRoot()],
      providers: [
        { provide: CashSessionService, useValue: api },
        { provide: ToastService, useValue: toast },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
    const fixture = TestBed.createComponent(CashSessionComponent);
    fixture.detectChanges();
    return { fixture, api, toast, confirm, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the cash to count on its own, never mixed with card or mobile money (QA D2714)', () => {
    const { el } = render([session()]);
    expect(el.querySelector('[data-testid="cash-to-count"]')?.textContent).toContain('500');
  });

  it('closes with every method counted; no explanation when nothing differs', async () => {
    const { fixture, api } = render([session()]);
    const c = fixture.componentInstance;
    const s = c.sessions()[0];
    c.setCounted(s, s.methods[0], 500);
    c.setCounted(s, s.methods[1], 850);
    await c.close(s);
    expect(api.close).toHaveBeenCalledWith('s1', [
      { method: 'CASH', operator: null, counted: 500, explanation: undefined },
      { method: 'MOBILE_MONEY', operator: 'ORANGE_MONEY', counted: 850, explanation: undefined },
    ]);
  });

  it('asks for an explanation only when the count differs (QA D2325)', async () => {
    const { fixture, api } = render([session()]);
    const c = fixture.componentInstance;
    const s = c.sessions()[0];
    c.setCounted(s, s.methods[0], 400);
    c.setCounted(s, s.methods[1], 850);
    expect(c.differenceOf(s, s.methods[0])).toBe(-100);
    await c.close(s);
    expect(api.close).not.toHaveBeenCalled();
    expect(fixture.componentInstance.refusals()['s1']).toBeTruthy();
    c.setExplanation(s, s.methods[0], 'Billet de 100 manquant');
    await c.close(s);
    expect(api.close).toHaveBeenCalled();
  });

  it('refuses a method left uncounted', async () => {
    const { fixture, api } = render([session()]);
    const c = fixture.componentInstance;
    const s = c.sessions()[0];
    c.setCounted(s, s.methods[0], 500);
    await c.close(s);
    expect(api.close).not.toHaveBeenCalled();
  });

  it('QA D3256 (r70): the close is confirmed on the counted figures; cancelling closes nothing', async () => {
    const { fixture, api, confirm } = render([session()], false);
    const c = fixture.componentInstance;
    const s = c.sessions()[0];
    c.setCounted(s, s.methods[0], 500);
    c.setCounted(s, s.methods[1], 850);
    await c.close(s);
    expect(confirm.ask).toHaveBeenCalledWith(
      jasmine.objectContaining({ titleKey: 'R70_MONEY.CASH.CLOSE_CONFIRM_TITLE' }),
    );
    expect(api.close).not.toHaveBeenCalled();
  });

  it('QA D3256 (r70): a negative amount carries the minus sign, never a hyphen', () => {
    const { fixture } = render([session()]);
    const text = fixture.componentInstance.signedCfa(-100);
    expect(text.startsWith('\u2212')).toBeTrue();
    expect(text).not.toContain('-');
  });

  it('a closed till shows its counts and no close button', () => {
    const closed = session({
      status: 'CLOSED',
      closeRound: 1,
      totalDifference: -100,
      counts: [
        {
          closeRound: 1,
          method: 'CASH',
          operator: null,
          expected: 500,
          counted: 400,
          difference: -100,
          explanation: 'Billet manquant',
        },
      ],
    });
    const { el } = render([closed]);
    expect(el.querySelector('[data-testid="cash-session-close-BILLING"]')).toBeNull();
    expect(el.textContent).toContain('Billet manquant');
  });

  const reopened = (mine: boolean) =>
    session({
      id: 'r',
      mine,
      status: 'OPEN',
      closeRound: 1,
      countedRound: 0,
      cashDifference: null,
      differenceLines: 0,
      reopenedAt: '2026-11-20T18:30:00',
      counts: [
        {
          closeRound: 1,
          method: 'CASH',
          operator: null,
          expected: 7600,
          counted: 7500,
          difference: -100,
          explanation: 'Billet de 100 manquant',
        },
      ],
      history: [
        {
          closeRound: 1,
          closedAt: '2026-11-20T18:00:00',
          cashExpected: 7600,
          cashCounted: 7500,
          cashDifference: -100,
          totalDifference: -100,
          differenceLines: 1,
        },
      ],
    });

  it('QA D3982 (r77): a reopened till shows no last count; the earlier close is listed apart', () => {
    const { fixture } = render([reopened(false)]);
    const c = fixture.componentInstance;
    const s = reopened(false);
    expect(c.lastCount(s, s.methods[0])).toBeNull();
    expect(c.earlierCounts(s).map((x) => x.closeRound)).toEqual([1]);
    // Closed again: round 2 is current, round 1 stays history.
    const again = session({
      ...s,
      status: 'CLOSED',
      closeRound: 2,
      countedRound: 2,
      counts: [...s.counts, { ...s.counts[0], closeRound: 2, counted: 7600, difference: 0 }],
    });
    expect(c.lastCount(again, again.methods[0])?.counted).toBe(7600);
    expect(c.earlierCounts(again).map((x) => x.closeRound)).toEqual([1]);
  });

  it('shows only the PHARMACY till on the pharmacy page', () => {
    const { fixture } = render([session(), session({ id: 's2', till: 'PHARMACY' })]);
    fixture.componentRef.setInput('till', 'PHARMACY');
    fixture.detectChanges();
    expect(fixture.componentInstance.sessions().map((s) => s.till)).toEqual(['PHARMACY']);
  });
});

describe('CashSupervisorComponent (billing plan PR 10, QA D2326)', () => {
  function render(rows: CashSession[], removedLines: RemovedInvoiceLine[] = []) {
    const api = jasmine.createSpyObj<CashSessionService>('CashSessionService', [
      'list',
      'removedLines',
      'reopen',
      'review',
      'dayReportPdf',
      'journalCsv',
    ]);
    api.list.and.returnValue(of(rows));
    api.removedLines.and.returnValue(of(removedLines));
    api.reopen.and.callFake((id) => of(session({ id, mine: false, status: 'OPEN' })));
    const toast = jasmine.createSpyObj<ToastService>('ToastService', [
      'success',
      'error',
      'info',
      'warning',
    ]);
    TestBed.configureTestingModule({
      imports: [CashSupervisorComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: CashSessionService, useValue: api },
        { provide: ToastService, useValue: toast },
      ],
    });
    const fixture = TestBed.createComponent(CashSupervisorComponent);
    fixture.detectChanges();
    return { fixture, api, toast, el: fixture.nativeElement as HTMLElement };
  }

  it("lists every till of the day; reopen and review are offered on another cashier's closed till only", () => {
    const { el } = render([
      session({ id: 'a', mine: false, status: 'CLOSED', closeRound: 1 }),
      session({ id: 'b', mine: false, status: 'OPEN' }),
      session({ id: 'c', mine: true, status: 'CLOSED', closeRound: 1 }),
    ]);
    expect(el.querySelectorAll('[data-testid^="cash-supervisor-row-"]').length).toBe(3);
    expect(el.querySelector('[data-testid="cash-reopen-a"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="cash-review-a"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="cash-reopen-b"]')).toBeNull();
    expect(el.querySelector('[data-testid="cash-reopen-c"]')).toBeNull();
  });

  it('PR 10 review: a shortage and a surplus that sum to zero are flagged, with the cash difference', () => {
    const { el, fixture } = render([
      session({
        id: 'a',
        mine: false,
        status: 'CLOSED',
        closeRound: 1,
        totalDifference: 0,
        cashDifference: -500,
        differenceLines: 2,
      }),
      session({
        id: 'b',
        mine: false,
        status: 'CLOSED',
        closeRound: 1,
        totalDifference: 0,
        cashDifference: 0,
        differenceLines: 0,
      }),
    ]);
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      BILLING: { CASH_SESSION: { CASH_DIFFERENCE_VALUE: 'Écart espèces : {{ amount }}' } },
    });
    translate.use('fr');
    fixture.detectChanges();
    const differing = el.querySelector('[data-testid="cash-difference-a"]') as HTMLElement;
    expect(differing.classList).toContain('gap');
    expect(differing.textContent).toContain('500');
    expect(el.querySelector('[data-testid="cash-difference-flag-a"]')).not.toBeNull();
    const balanced = el.querySelector('[data-testid="cash-difference-b"]') as HTMLElement;
    expect(balanced.classList).not.toContain('gap');
    // The table keeps seven columns: the difference sits under the cash to count.
    expect(el.querySelectorAll('[data-testid="cash-supervisor-table"] thead th').length).toBe(7);
    expect(el.querySelector('[data-testid="cash-difference-flag-b"]')).toBeNull();
  });

  it('PR 10 review (QA D2703): the lines removed from invoices that day are listed with who and why', () => {
    const { el, api } = render(
      [session({ id: 'a', mine: false, status: 'OPEN' })],
      [
        {
          id: 'r1',
          invoiceNumber: 'TWP-2026-000040',
          line: 'Radiographie thorax',
          quantity: 1,
          unitPrice: 0,
          reason: 'Acte non réalisé',
          removedByName: 'Awa Caisse',
        },
      ],
    );
    expect(api.removedLines).toHaveBeenCalled();
    const row = el.querySelector('[data-testid="cash-removed-line-r1"]') as HTMLElement;
    expect(row.textContent).toContain('TWP-2026-000040');
    expect(row.textContent).toContain('Radiographie thorax');
    expect(row.textContent).toContain('Acte non réalisé');
    expect(row.textContent).toContain('Awa Caisse');
  });

  it('no removed line, no section', () => {
    const { el } = render([session({ id: 'a', mine: false, status: 'OPEN' })]);
    expect(el.querySelector('[data-testid="cash-removed-lines"]')).toBeNull();
  });

  const reopened = (mine: boolean) =>
    session({
      id: 'r',
      mine,
      status: 'OPEN',
      closeRound: 1,
      countedRound: 0,
      cashDifference: null,
      differenceLines: 0,
      reopenedAt: '2026-11-20T18:30:00',
      counts: [
        {
          closeRound: 1,
          method: 'CASH',
          operator: null,
          expected: 7600,
          counted: 7500,
          difference: -100,
          explanation: 'Billet de 100 manquant',
        },
      ],
      history: [
        {
          closeRound: 1,
          closedAt: '2026-11-20T18:00:00',
          cashExpected: 7600,
          cashCounted: 7500,
          cashDifference: -100,
          totalDifference: -100,
          differenceLines: 1,
        },
      ],
    });

  it('QA D3982 (r77): a reopened till shows no old count or variance; its earlier close is history', () => {
    const { el, fixture } = render([reopened(false)]);
    const c = fixture.componentInstance;
    const s = c.sessions()[0];
    expect(c.countedOf(s, s.methods[0])).toBeNull();
    expect(c.explanationsOf(s)).toEqual([]);
    expect(el.querySelector('[data-testid="cash-difference-r"]')).toBeNull();
    expect(el.querySelector('[data-testid="cash-difference-flag-r"]')).toBeNull();
    c.toggle(s);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="cash-history-round-1"]')).not.toBeNull();
  });

  it('reopening needs a reason', () => {
    const closed = session({ id: 'a', mine: false, status: 'CLOSED', closeRound: 1 });
    const { fixture, api } = render([closed]);
    const c = fixture.componentInstance;
    c.ask(closed, 'reopen');
    c.submitAsk();
    expect(api.reopen).not.toHaveBeenCalled();
    expect(c.noteRefused()).toBeTrue();
    c.note.set('Paiement oublié');
    c.submitAsk();
    expect(api.reopen).toHaveBeenCalledWith('a', 'Paiement oublié');
  });
});
