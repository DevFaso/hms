import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MyBillingComponent } from './my-billing.component';
import { PatientPortalService, PortalInvoice } from '../../services/patient-portal.service';

/**
 * The patient's statements and the pay-an-invoice dialog. Asserted through the
 * rendered DOM in French (the #661 shape): the refusal, the success line and
 * the fallback title only count once the page shows them translated.
 */
describe('MyBillingComponent', () => {
  let fixture: ComponentFixture<MyBillingComponent>;
  let component: MyBillingComponent;
  let portal: jasmine.SpyObj<PatientPortalService>;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (selector: string): string =>
    host().querySelector(selector)?.textContent?.trim() ?? '';

  function invoice(overrides: Partial<PortalInvoice> = {}): PortalInvoice {
    return {
      id: 'inv1',
      invoiceNumber: '001',
      date: '2026-09-01',
      dueDate: '2026-09-30',
      amount: 100,
      balance: 100,
      status: 'SENT',
      facility: '',
      description: '',
      ...overrides,
    };
  }

  function create(invoices: PortalInvoice[]): void {
    portal.getMyInvoices.and.returnValue(of(invoices));
    fixture = TestBed.createComponent(MyBillingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function openDialog(): void {
    (host().querySelector('.pay-btn') as HTMLElement).click();
    fixture.detectChanges();
  }

  beforeEach(() => {
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', [
      'getMyInvoices',
      'payInvoice',
    ]);
    TestBed.configureTestingModule({
      imports: [MyBillingComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: portal }],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: {
        BILLING: {
          EMPTY_TITLE: 'Aucune facture',
          MEDICAL_SERVICES: 'Services médicaux',
          PAY_NOW: 'Payer',
          MAKE_PAYMENT: 'Effectuer un paiement',
          AMOUNT_EXCEEDS: 'Le montant dépasse le solde dû',
          PAYMENT_SUCCESS_AMOUNT: 'Paiement de {{amount}} enregistré',
          PAYMENT_FAILED: 'Le paiement a échoué',
        },
      },
    });
  });

  it('shows the translated empty state when there is no invoice', () => {
    create([]);
    expect(text('.portal-empty h3')).toBe('Aucune facture');
  });

  it('titles an invoice with no description or facility in French', () => {
    create([invoice()]);
    expect(text('.pli-title')).toBe('Services médicaux');
  });

  it('offers Pay only on an unpaid invoice that has been issued', () => {
    create([
      invoice({ id: 'a' }),
      invoice({ id: 'b', status: 'DRAFT' }),
      invoice({ id: 'c', balance: 0, status: 'PAID' }),
    ]);
    expect(host().querySelectorAll('.pay-btn').length).toBe(1);
    expect(text('.pay-btn')).toContain('Payer');
  });

  it('opens the dialog prefilled with the balance', () => {
    create([invoice({ balance: 75 })]);
    openDialog();
    expect(text('[role="dialog"] h3')).toContain('Effectuer un paiement');
    expect(component.payAmount).toBe(75);
  });

  it('refuses an amount above the balance on screen, without calling the server', () => {
    create([invoice({ balance: 50 })]);
    openDialog();
    component.payAmount = 100;
    (host().querySelector('.pay-submit-btn') as HTMLElement).click();
    fixture.detectChanges();
    expect(portal.payInvoice).not.toHaveBeenCalled();
    expect(text('.pay-error')).toBe('Le montant dépasse le solde dû');
  });

  it('sends the method and reference with the amount, and confirms in French', () => {
    portal.payInvoice.and.returnValue(of(invoice({ balance: 0, status: 'PAID' })));
    create([invoice({ balance: 50 })]);
    openDialog();
    component.payAmount = 50;
    component.payMethod = 'MOBILE_MONEY';
    component.payReference = 'MM-123';
    (host().querySelector('.pay-submit-btn') as HTMLElement).click();
    fixture.detectChanges();

    expect(portal.payInvoice).toHaveBeenCalledWith('inv1', {
      amount: 50,
      paymentMethod: 'MOBILE_MONEY',
      transactionReference: 'MM-123',
    });
    expect(text('.pay-success')).toBe('Paiement de 50.00 enregistré');
    expect(portal.getMyInvoices).toHaveBeenCalledTimes(2);
  });

  it('shows the translated failure when the server gives no message', () => {
    portal.payInvoice.and.returnValue(throwError(() => ({ status: 500 })));
    create([invoice()]);
    openDialog();
    (host().querySelector('.pay-submit-btn') as HTMLElement).click();
    fixture.detectChanges();
    expect(text('.pay-error')).toBe('Le paiement a échoué');
    expect(host().querySelector('.pay-success')).toBeNull();
  });

  it('closes the dialog', () => {
    create([invoice()]);
    openDialog();
    (host().querySelector('.pay-dialog-close') as HTMLElement).click();
    fixture.detectChanges();
    expect(host().querySelector('[role="dialog"]')).toBeNull();
  });
});
