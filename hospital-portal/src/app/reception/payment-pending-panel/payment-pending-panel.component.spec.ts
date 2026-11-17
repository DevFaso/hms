import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PaymentPendingPanelComponent, newestPayment } from './payment-pending-panel.component';
import { BillingService, PaymentRow } from '../../services/billing.service';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { ReceptionService } from '../reception.service';
import { ToastService } from '../../core/toast.service';
import { of } from 'rxjs';
import { amountStillDue, pendingDeclared } from '../../shared/payment-prefill';

describe('PaymentPendingPanelComponent', () => {
  let component: PaymentPendingPanelComponent;
  let fixture: ComponentFixture<PaymentPendingPanelComponent>;

  const mockReceptionService = {
    recordPayment: (_invoiceId: string, _amount: number, _method?: string, _reference?: string) =>
      of({}),
  };

  const mockToastService = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PaymentPendingPanelComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: ReceptionService, useValue: mockReceptionService },
        { provide: ToastService, useValue: mockToastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PaymentPendingPanelComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no items', () => {
    component.items = [];
    fixture.detectChanges();
    const el = fixture.nativeElement.querySelector('.empty-state');
    expect(el).toBeTruthy();
  });

  it('should open pay dialog with item', () => {
    const item = { patientId: 'p1', patientName: 'Test', appointmentId: 'a1' } as any;
    component.openPayDialog(item);
    expect(component.payingItem()).toEqual(item);
    expect(component.payMethod()).toBe('CASH');
  });

  it('should close pay dialog', () => {
    component.payingItem.set({ patientId: 'p1' } as any);
    component.closePayDialog();
    expect(component.payingItem()).toBeNull();
  });

  it('should not submit when no invoice selected', () => {
    component.selectedInvoiceId.set(null);
    component.submitPayment();
    expect(component.paying()).toBe(false);
  });

  it('QA D1089: the record button is never dead; an empty amount is marked and named', () => {
    const spy = spyOn(mockReceptionService, 'recordPayment').and.returnValue(of({}));
    component.payingItem.set({ patientId: 'p1', patientName: 'Seydou' } as any);
    component.openInvoices.set([
      {
        id: 'inv-1',
        invoiceNumber: 'F-1',
        totalAmount: 2000,
        amountPaid: 0,
        currency: 'XOF',
      } as any,
    ]);
    component.selectedInvoiceId.set('inv-1');
    component.payAmount.set(null);
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    const record = Array.from(
      host.querySelectorAll<HTMLButtonElement>('.modal-footer button'),
    ).find((b) => b.textContent?.includes('RECEPTION.RECORD_PAYMENT'))!;
    expect(record.disabled).toBeFalse();

    record.click();
    fixture.detectChanges();

    expect(spy).not.toHaveBeenCalled();
    expect(host.querySelector('#payAmt')!.getAttribute('aria-invalid')).toBe('true');
    expect(host.textContent).toContain('BILLING.PAYMENT_AMOUNT_REQUIRED');
  });

  it('QA D1296: the placeholder invoice is refused on the client, never posted as "null"', () => {
    const spy = spyOn(mockReceptionService, 'recordPayment').and.returnValue(of({}));
    component.payingItem.set({ patientId: 'p1', patientName: 'Ousmane' } as any);
    component.openInvoices.set([
      {
        id: 'inv-1',
        invoiceNumber: 'F-1',
        totalAmount: 2000,
        amountPaid: 0,
        currency: 'XOF',
      } as any,
      {
        id: 'inv-2',
        invoiceNumber: 'F-2',
        totalAmount: 500,
        amountPaid: 0,
        currency: 'XOF',
      } as any,
    ]);
    component.selectedInvoiceId.set('inv-1');
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    const select = host.querySelector<HTMLSelectElement>('#invSelect')!;
    // Back to "-- Sélectionner la facture --", as the receptionist did.
    select.selectedIndex = 0;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(component.selectedInvoiceId()).toBeNull();
    component.payAmount.set(100);
    component.submitPayment();
    fixture.detectChanges();

    expect(spy).not.toHaveBeenCalled();
    expect(select.getAttribute('aria-invalid')).toBe('true');
    expect(host.querySelector('#payAmt')!.hasAttribute('aria-invalid')).toBeFalse();
    // Every control is declared required and starred.
    for (const id of ['invSelect', 'payAmt', 'payMeth']) {
      expect(host.querySelector('#' + id)!.hasAttribute('required'))
        .withContext(id)
        .toBeTrue();
      expect(host.querySelector(`label[for="${id}"]`)!.textContent)
        .withContext(id)
        .toContain('*');
    }
  });

  it('QA D1296: a blank invoice and a blank amount are both named in one press', () => {
    component.payingItem.set({ patientId: 'p1', patientName: 'Ousmane' } as any);
    component.openInvoices.set([
      {
        id: 'inv-1',
        invoiceNumber: 'F-1',
        totalAmount: 2000,
        amountPaid: 0,
        currency: 'XOF',
      } as any,
      {
        id: 'inv-2',
        invoiceNumber: 'F-2',
        totalAmount: 500,
        amountPaid: 0,
        currency: 'XOF',
      } as any,
    ]);
    component.selectedInvoiceId.set(null);
    component.payAmount.set(null);
    fixture.detectChanges();
    component.submitPayment();
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    expect(host.querySelector('#invSelect')!.getAttribute('aria-invalid')).toBe('true');
    expect(host.querySelector('#payAmt')!.getAttribute('aria-invalid')).toBe('true');
    expect(host.querySelector('#invSelect-invalid-msg')!.textContent).toBe('COMMON.FIELD_REQUIRED');
    expect(host.querySelector('#payAmt-invalid-msg')!.textContent).toBe(
      'BILLING.PAYMENT_AMOUNT_REQUIRED',
    );
  });

  it('QA D948: offers Mobile Money, needs its reference, and sends it', () => {
    expect(component.methods.map((m) => m.value)).toContain('MOBILE_MONEY');
    const spy = spyOn(mockReceptionService, 'recordPayment').and.returnValue(of({}));
    component.payingItem.set({ patientId: 'p1', patientName: 'Seydou' } as any);
    component.selectedInvoiceId.set('inv-1');
    component.payAmount.set(1500);
    component.payMethod.set('MOBILE_MONEY');

    component.submitPayment();
    expect(spy).not.toHaveBeenCalled();

    component.payReference.set(' OM-778812 ');
    component.submitPayment();
    expect(spy).toHaveBeenCalledWith('inv-1', 1500, 'MOBILE_MONEY', 'OM-778812');
  });

  it('QA D948: shows francs CFA without decimals', () => {
    component.payingItem.set({ patientId: 'p1', patientName: 'Seydou' } as any);
    component.openInvoices.set([
      {
        id: 'inv-1',
        invoiceNumber: 'F-1',
        totalAmount: 1500,
        amountPaid: 0,
        balanceDue: 1500,
        status: 'SENT',
      },
    ] as any);
    component.loadingInvoices.set(false);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('F\u00a0CFA');
    expect(text).not.toContain('1 500,00');
  });

  it('QA D1022: a collected payment hands over the receipt of that payment', () => {
    const billing = TestBed.inject(BillingService);
    spyOn(billing, 'listPayments').and.returnValue(
      of([
        {
          id: 'pay-old',
          amount: 500,
          paymentDate: '2026-10-28',
          paymentMethod: 'CASH',
          status: 'COMPLETED',
          createdAt: '2026-10-28T09:00:00',
        },
        {
          id: 'pay-new',
          amount: 2000,
          paymentDate: '2026-10-29',
          paymentMethod: 'MOBILE_MONEY',
          status: 'COMPLETED',
          createdAt: '2026-10-29T10:15:00',
        },
      ] as unknown as PaymentRow[]),
    );
    const receipt = spyOn(billing, 'getReceiptPdf').and.returnValue(
      of(new HttpResponse<Blob>({ body: new Blob(['%PDF']) })),
    );
    component.payingItem.set({ patientId: 'p1', patientName: 'Boukaré' } as any);
    component.selectedInvoiceId.set('inv-16');
    component.payAmount.set(2000);
    component.payMethod.set('CASH');

    component.submitPayment();

    expect(receipt).toHaveBeenCalledWith('inv-16', 'pay-new');
  });

  it('newestPayment picks the last recorded payment', () => {
    expect(newestPayment([])).toBeNull();
    expect(
      newestPayment([
        { id: 'a', paymentDate: '2026-10-29', createdAt: '2026-10-29T08:00:00' },
        { id: 'b', paymentDate: '2026-10-29', createdAt: '2026-10-29T11:00:00' },
      ] as unknown as PaymentRow[])?.id,
    ).toBe('b');
  });

  describe('QA D1321 (round 29): a pending patient declaration', () => {
    const invoice = {
      id: 'inv-4',
      invoiceNumber: 'CHWF6646-2026-000004',
      totalAmount: 1250,
      amountPaid: 0,
      balanceDue: 1250,
      status: 'SENT',
      currency: 'XOF',
    };

    it('prefills only what is still due, says the declaration, and refuses more', () => {
      const billing = TestBed.inject(BillingService);
      spyOn(billing, 'listPayments').and.returnValue(
        of([
          { id: 'p1', status: 'PENDING', amount: 1200 },
          { id: 'p0', status: 'CONFIRMED', amount: 100 },
        ] as unknown as PaymentRow[]),
      );
      const record = spyOn(mockReceptionService, 'recordPayment').and.callThrough();
      component.payingItem.set({ patientId: 'p1', patientName: 'Seydou' } as any);
      component.openInvoices.set([invoice as any]);
      component.selectInvoice('inv-4');
      fixture.detectChanges();
      expect(component.pending()).toBe(1200);
      expect(component.payAmount()).toBe(50);
      const host: HTMLElement = fixture.nativeElement;
      expect(host.querySelector('[data-testid="desk-pay-pending"]')).not.toBeNull();
      component.payAmount.set(1250);
      component.submitPayment();
      expect(record).not.toHaveBeenCalled();
      expect(host.querySelector('#payAmt-invalid-msg')!.textContent).toBe(
        'RECEPTION.PAYMENT_EXCEEDS_WITH_PENDING',
      );
    });

    it('keeps the full balance when nothing is pending, and an amount the reader typed', () => {
      const billing = TestBed.inject(BillingService);
      spyOn(billing, 'listPayments').and.returnValue(of([] as PaymentRow[]));
      component.openInvoices.set([invoice as any]);
      component.selectInvoice('inv-4');
      expect(component.payAmount()).toBe(1250);
      expect(amountStillDue(1250, 1250)).toBeNull();
      expect(
        pendingDeclared([
          { status: 'PENDING', amount: 10 },
          { status: 'REJECTED', amount: 5 },
        ]),
      ).toBe(10);
    });
  });
});
