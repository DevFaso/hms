import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { CashClosingComponent } from './cash-closing.component';
import { BillingService, CashClosing } from '../../services/billing.service';

/** QA D1059: the cashier's daily cash closing. */
describe('CashClosingComponent (QA D1059)', () => {
  const closing: CashClosing = {
    date: '2026-10-29',
    cashierName: 'Awa Billing',
    byMethod: [
      { source: 'BILLING', method: 'CASH', count: 2, amount: 4500 },
      { source: 'PHARMACY', method: 'MOBILE_MONEY', count: 1, amount: 3600 },
    ],
    entries: [
      { id: 'p1', source: 'BILLING', amount: 1500, status: 'CONFIRMED', method: 'CASH' },
      { id: 'p2', source: 'BILLING', amount: 3000, status: 'CONFIRMED', method: 'CASH' },
      { id: 'p3', source: 'PHARMACY', amount: 3600, status: 'POSTED', method: 'MOBILE_MONEY' },
      { id: 'p4', source: 'BILLING', amount: 500, status: 'REVERSED', method: 'CASH' },
    ],
    receivedCount: 3,
    receivedAmount: 8100,
    reversedCount: 1,
    reversedAmount: 500,
  };

  function render(answer: CashClosing) {
    const billing = jasmine.createSpyObj<BillingService>('BillingService', ['getCashClosing']);
    billing.getCashClosing.and.returnValue(of(answer));
    TestBed.configureTestingModule({
      imports: [CashClosingComponent, TranslateModule.forRoot()],
      providers: [{ provide: BillingService, useValue: billing }],
    });
    const fixture = TestBed.createComponent(CashClosingComponent);
    fixture.detectChanges();
    return { fixture, billing, el: fixture.nativeElement as HTMLElement };
  }

  it("loads today's closing, then the day the cashier picks", () => {
    const { fixture, billing } = render(closing);
    expect(billing.getCashClosing).toHaveBeenCalledTimes(1);
    fixture.componentInstance.pick('2026-10-28');
    expect(billing.getCashClosing).toHaveBeenCalledWith('2026-10-28');
  });

  it('totals per till and method, lists every payment, and keeps a reversed one out of the total', () => {
    const { el } = render(closing);
    expect(el.querySelectorAll('[data-testid="cash-closing-totals"] tbody tr').length).toBe(2);
    expect(el.querySelectorAll('[data-testid="cash-closing-entries"] tbody tr').length).toBe(4);
    expect(el.querySelectorAll('[data-testid="cash-closing-entries"] tr.reversed').length).toBe(1);
    expect(el.querySelector('[data-testid="cash-closing-total"]')?.textContent).toContain('8');
  });

  it('says so when nothing was received', () => {
    const { el } = render({ ...closing, entries: [], byMethod: [], receivedCount: 0 });
    expect(el.querySelector('[data-testid="cash-closing-empty"]')).not.toBeNull();
  });
});
