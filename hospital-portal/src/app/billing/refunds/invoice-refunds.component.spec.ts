import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { InvoiceRefundsComponent, newRequestKey } from './invoice-refunds.component';
import {
  BillingInvoiceResponse,
  BillingService,
  InvoiceRefund,
} from '../../services/billing.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';

/** Billing-money plan PR 8 (QA D1900): the refunds of an invoice, asked for, approved by another person, handed over. */
describe('InvoiceRefundsComponent (QA D1900)', () => {
  let fixture: ComponentFixture<InvoiceRefundsComponent>;
  let billing: jasmine.SpyObj<BillingService>;
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  function invoice(overrides: Partial<BillingInvoiceResponse> = {}): BillingInvoiceResponse {
    return {
      id: 'inv-13',
      invoiceNumber: 'CHWF-2026-000013',
      totalAmount: 1500,
      amountPaid: 2000,
      balanceDue: 0,
      refundDue: 500,
      status: 'PAID',
      ...overrides,
    } as BillingInvoiceResponse;
  }

  const requested: InvoiceRefund = {
    id: 'rf-1',
    invoiceId: 'inv-13',
    amount: 500,
    method: 'CASH',
    reason: 'Trop-perçu',
    status: 'REQUESTED',
    requestedByName: 'Awa Caisse',
    requestedByMe: false,
  };

  function render(roles: string[], inv: BillingInvoiceResponse, rows: InvoiceRefund[] = []): void {
    billing = jasmine.createSpyObj<BillingService>('BillingService', [
      'listRefunds',
      'requestRefund',
      'approveRefund',
      'rejectRefund',
      'payRefund',
      'cancelRefund',
      'getRefundReceiptPdf',
    ]);
    billing.listRefunds.and.returnValue(of(rows));
    TestBed.configureTestingModule({
      imports: [InvoiceRefundsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: BillingService, useValue: billing },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    });
    fixture = TestBed.createComponent(InvoiceRefundsComponent);
    fixture.componentRef.setInput('invoice', inv);
    fixture.detectChanges();
  }

  afterEach(() => {
    document.querySelectorAll('.modal-backdrop').forEach((el) => el.remove());
  });

  it('shows "Rembourser" only when something is owed back', () => {
    render(['ROLE_BILLING_SPECIALIST'], invoice({ refundDue: 500 }));
    expect(host().querySelector('[data-testid="refund-request-open"]')).not.toBeNull();

    TestBed.resetTestingModule();
    render(['ROLE_BILLING_SPECIALIST'], invoice({ refundDue: 0 }));
    expect(host().querySelector('[data-testid="refund-request-open"]')).toBeNull();
  });

  it('never shows "Rembourser" to a role that cannot ask (the platform super-admin)', () => {
    render(['ROLE_SUPER_ADMIN'], invoice());
    expect(host().querySelector('[data-testid="refund-request-open"]')).toBeNull();
  });

  it('the request dialog takes focus, Escape closes it and gives focus back', fakeAsync(() => {
    render(['ROLE_BILLING_SPECIALIST'], invoice());
    const open = host().querySelector('[data-testid="refund-request-open"]') as HTMLButtonElement;
    document.body.appendChild(fixture.nativeElement);
    open.focus();
    open.click();
    fixture.detectChanges();
    tick(50);
    const dialog = host().querySelector('[data-testid="refund-request-dialog"]') as HTMLElement;
    expect(dialog).not.toBeNull();
    expect(dialog.contains(document.activeElement))
      .withContext('focus inside the dialog')
      .toBeTrue();
    // The amount starts at what is owed back, and the no-card notice is shown.
    expect(fixture.componentInstance.amount()).toBe(500);
    expect(host().querySelector('[data-testid="no-card-notice"]')).not.toBeNull();

    dialog.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();
    tick(50);
    expect(host().querySelector('[data-testid="refund-request-dialog"]')).toBeNull();
    expect(document.activeElement).toBe(open);
    fixture.nativeElement.remove();
  }));

  it('an empty submit names the amount and the reason, and sends nothing', fakeAsync(() => {
    render(['ROLE_BILLING_SPECIALIST'], invoice());
    fixture.componentInstance.openRequest();
    fixture.componentInstance.amount.set(null);
    fixture.detectChanges();
    (host().querySelector('[data-testid="refund-request-submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    tick();
    expect(host().querySelector('#refund-amount')?.getAttribute('aria-invalid')).toBe('true');
    expect(host().querySelector('#refund-reason')?.getAttribute('aria-invalid')).toBe('true');
    expect(billing.requestRefund).not.toHaveBeenCalled();
  }));

  it('PR 8 review: mobile money is asked for without its reference, which the hand-over asks for', fakeAsync(() => {
    render(['ROLE_BILLING_SPECIALIST'], invoice(), [
      { ...requested, id: 'rf-mm', status: 'APPROVED', method: 'MOBILE_MONEY' },
    ]);
    billing.requestRefund.and.returnValue(of(requested));
    const c = fixture.componentInstance;
    c.openRequest();
    c.method.set('MOBILE_MONEY');
    c.reason.set('Trop-perçu');
    c.submitRequest();
    expect(billing.requestRefund).toHaveBeenCalled();

    // The hand-over of an approved mobile-money refund opens its own dialog; an empty submit marks both fields.
    (
      host().querySelector('[data-testid="refund-rf-mm"] [data-testid="refund-pay"]') as HTMLElement
    ).click();
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="refund-pay-dialog"]')).not.toBeNull();
    (host().querySelector('[data-testid="refund-pay-submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    tick();
    expect(host().querySelector('#refund-pay-operator')?.getAttribute('aria-invalid')).toBe('true');
    expect(host().querySelector('#refund-pay-reference')?.getAttribute('aria-invalid')).toBe(
      'true',
    );
    expect(billing.payRefund).not.toHaveBeenCalled();

    billing.payRefund.and.returnValue(of({ ...requested, status: 'PAID' }));
    c.payOperator.set('ORANGE_MONEY');
    c.payReference.set('OM-OUT-9');
    c.submitHandOver();
    expect(billing.payRefund).toHaveBeenCalledWith('inv-13', 'rf-mm', {
      reference: 'OM-OUT-9',
      mobileMoneyOperator: 'ORANGE_MONEY',
    });
  }));

  it('PR 8 review: the idempotency key is a v4 UUID, also without crypto.randomUUID', () => {
    const v4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    expect(newRequestKey()).toMatch(v4);
    const original = globalThis.crypto.randomUUID;
    try {
      Object.defineProperty(globalThis.crypto, 'randomUUID', {
        value: undefined,
        configurable: true,
      });
      expect(newRequestKey()).toMatch(v4);
    } finally {
      Object.defineProperty(globalThis.crypto, 'randomUUID', {
        value: original,
        configurable: true,
      });
    }
  });

  it('sends the request with an idempotency key', () => {
    render(['ROLE_BILLING_SPECIALIST'], invoice());
    billing.requestRefund.and.returnValue(of(requested));
    const c = fixture.componentInstance;
    c.openRequest();
    c.reason.set('Trop-perçu du ticket modérateur');
    c.submitRequest();
    expect(billing.requestRefund).toHaveBeenCalledWith(
      'inv-13',
      jasmine.objectContaining({
        amount: 500,
        method: 'CASH',
        reason: 'Trop-perçu du ticket modérateur',
      }),
    );
    const sent = billing.requestRefund.calls.mostRecent().args[1];
    expect(sent.clientRequestId).toBeTruthy();
  });

  it('an approver decides a request someone else made, never their own (D-3)', () => {
    render(['ROLE_HOSPITAL_ADMIN'], invoice(), [
      requested,
      { ...requested, id: 'rf-2', requestedByMe: true },
    ]);
    expect(
      host().querySelector('[data-testid="refund-rf-1"] [data-testid="refund-approve"]'),
    ).not.toBeNull();
    expect(
      host().querySelector('[data-testid="refund-rf-2"] [data-testid="refund-approve"]'),
    ).toBeNull();
    expect(
      host().querySelector('[data-testid="refund-rf-2"] [data-testid="refund-own-request"]'),
    ).not.toBeNull();
  });

  it('a cashier hands over an approved refund but cannot approve one', () => {
    render(['ROLE_BILLING_SPECIALIST'], invoice(), [
      requested,
      { ...requested, id: 'rf-3', status: 'APPROVED' },
    ]);
    expect(host().querySelector('[data-testid="refund-approve"]')).toBeNull();
    expect(
      host().querySelector('[data-testid="refund-rf-3"] [data-testid="refund-pay"]'),
    ).not.toBeNull();
  });

  it('a handed-over refund shows when and by whom, with its receipt', () => {
    render(['ROLE_BILLING_SPECIALIST'], invoice({ refundDue: 0, amountRefunded: 500 }), [
      { ...requested, status: 'PAID', paidAt: '2026-11-17T15:30:00', paidByName: 'Awa Caisse' },
    ]);
    expect(host().querySelector('[data-testid="refund-paid-on"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="refund-receipt"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="refunded-total"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="refund-cancel"]')).toBeNull();
  });

  it('rejecting asks for the reason where it is typed', fakeAsync(() => {
    render(['ROLE_ACCOUNTANT'], invoice(), [requested]);
    fixture.componentInstance.openDecision(requested, 'reject');
    fixture.detectChanges();
    (host().querySelector('[data-testid="refund-decision-submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    tick();
    expect(host().querySelector('#refund-decision-reason')?.getAttribute('aria-invalid')).toBe(
      'true',
    );
    expect(billing.rejectRefund).not.toHaveBeenCalled();
  }));
});
