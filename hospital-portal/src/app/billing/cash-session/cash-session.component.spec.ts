import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { CashSessionComponent } from './cash-session.component';
import { CashSupervisorComponent } from './cash-supervisor.component';
import { CashSession, CashSessionService } from '../../services/cash-session.service';
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
  function render(rows: CashSession[]) {
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
    TestBed.configureTestingModule({
      imports: [CashSessionComponent, TranslateModule.forRoot()],
      providers: [
        { provide: CashSessionService, useValue: api },
        { provide: ToastService, useValue: toast },
      ],
    });
    const fixture = TestBed.createComponent(CashSessionComponent);
    fixture.detectChanges();
    return { fixture, api, toast, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the cash to count on its own, never mixed with card or mobile money (QA D2714)', () => {
    const { el } = render([session()]);
    expect(el.querySelector('[data-testid="cash-to-count"]')?.textContent).toContain('500');
  });

  it('closes with every method counted; no explanation when nothing differs', () => {
    const { fixture, api } = render([session()]);
    const c = fixture.componentInstance;
    const s = c.sessions()[0];
    c.setCounted(s, s.methods[0], 500);
    c.setCounted(s, s.methods[1], 850);
    c.close(s);
    expect(api.close).toHaveBeenCalledWith('s1', [
      { method: 'CASH', operator: null, counted: 500, explanation: undefined },
      { method: 'MOBILE_MONEY', operator: 'ORANGE_MONEY', counted: 850, explanation: undefined },
    ]);
  });

  it('asks for an explanation only when the count differs (QA D2325)', () => {
    const { fixture, api } = render([session()]);
    const c = fixture.componentInstance;
    const s = c.sessions()[0];
    c.setCounted(s, s.methods[0], 400);
    c.setCounted(s, s.methods[1], 850);
    expect(c.differenceOf(s, s.methods[0])).toBe(-100);
    c.close(s);
    expect(api.close).not.toHaveBeenCalled();
    expect(fixture.componentInstance.refusals()['s1']).toBeTruthy();
    c.setExplanation(s, s.methods[0], 'Billet de 100 manquant');
    c.close(s);
    expect(api.close).toHaveBeenCalled();
  });

  it('refuses a method left uncounted', () => {
    const { fixture, api } = render([session()]);
    const c = fixture.componentInstance;
    const s = c.sessions()[0];
    c.setCounted(s, s.methods[0], 500);
    c.close(s);
    expect(api.close).not.toHaveBeenCalled();
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

  it('shows only the PHARMACY till on the pharmacy page', () => {
    const { fixture } = render([session(), session({ id: 's2', till: 'PHARMACY' })]);
    fixture.componentRef.setInput('till', 'PHARMACY');
    fixture.detectChanges();
    expect(fixture.componentInstance.sessions().map((s) => s.till)).toEqual(['PHARMACY']);
  });
});

describe('CashSupervisorComponent (billing plan PR 10, QA D2326)', () => {
  function render(rows: CashSession[]) {
    const api = jasmine.createSpyObj<CashSessionService>('CashSessionService', [
      'list',
      'reopen',
      'review',
      'dayReportPdf',
      'journalCsv',
    ]);
    api.list.and.returnValue(of(rows));
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
