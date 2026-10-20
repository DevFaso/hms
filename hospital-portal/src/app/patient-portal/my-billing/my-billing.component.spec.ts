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
      paid: 0,
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
          AMOUNT_EXCEEDS_PENDING: 'Au plus {{max}} : {{pending}} en attente',
          PENDING_DECLARED: 'Déjà déclaré',
          CREDITED_TO: 'Reporté sur la facture {{number}}',
          PAYMENT_PENDING: 'Paiement de {{amount}} déclaré, en attente de confirmation',
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

  it('QA D202: a cancelled invoice is neither owed nor payable', () => {
    create([
      invoice({ id: 'a', balance: 7900 }),
      invoice({ id: 'b', balance: 5000, status: 'CANCELLED' }),
    ]);
    expect(component.totalBalance()).toBe(7900);
    expect(host().querySelectorAll('.pay-btn').length).toBe(1);
  });

  it('QA D254: a paid invoice shows its total, not 0', () => {
    create([invoice({ id: 'c', amount: 2500, paid: 2500, balance: 0, status: 'PAID' })]);
    expect(text('.pli-amount').replace(/\D/g, '')).toBe('2500');
  });

  it('opens the dialog prefilled with the balance', () => {
    create([invoice({ balance: 75 })]);
    openDialog();
    expect(text('[role="dialog"] h3')).toContain('Effectuer un paiement');
    expect(component.payAmount).toBe(75);
  });

  it('QA D421: the dialog shows what is pending and prefills only what is left to declare', () => {
    // The QA: 150 owed, 100 MOBILE_MONEY + 50 CARD already declared.
    create([invoice({ balance: 300, amount: 300, pending: 150 })]);
    openDialog();
    expect(component.payAmount).toBe(150);
    expect(text('.pay-pending')).toContain('Déjà déclaré');
  });

  it('QA D421: an amount above what is left is refused in the figures on screen', () => {
    create([invoice({ balance: 300, amount: 300, pending: 150 })]);
    openDialog();
    component.payAmount = 200;
    (host().querySelector('.pay-submit-btn') as HTMLElement).click();
    fixture.detectChanges();
    expect(portal.payInvoice).not.toHaveBeenCalled();
    expect(text('.pay-error')).toBe('Au plus 150 : 150 en attente');
  });

  it('QA D428: a folded copay names the invoice it was credited to', () => {
    create([
      invoice({
        id: 'cp',
        balance: 0,
        paid: 0,
        amount: 1000,
        status: 'CANCELLED',
        creditedTo: 'RHA-21',
      }),
    ]);
    expect(text('.pli-status')).toBe('Reporté sur la facture RHA-21');
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

  it('sends the method and reference with the amount, and says it awaits confirmation (QA D140)', () => {
    // The server records a declaration as PENDING: the invoice is unchanged.
    portal.payInvoice.and.returnValue(of(invoice({ balance: 50 })));
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
    expect(text('.pay-success')).toBe('Paiement de 50 déclaré, en attente de confirmation');
    expect(portal.getMyInvoices).toHaveBeenCalledTimes(2);
  });

  it('refuses a fractional franc amount without calling the server (QA D184)', () => {
    create([invoice({ balance: 50 })]);
    openDialog();
    component.payAmount = 0.5;
    (host().querySelector('.pay-submit-btn') as HTMLElement).click();
    fixture.detectChanges();
    expect(portal.payInvoice).not.toHaveBeenCalled();
  });

  it('refuses a card payment with no reference without calling the server (QA D433)', () => {
    create([invoice()]);
    openDialog();
    component.payMethod = 'CARD';
    component.payReference = '   ';
    (host().querySelector('.pay-submit-btn') as HTMLElement).click();
    fixture.detectChanges();
    expect(portal.payInvoice).not.toHaveBeenCalled();
    expect(text('.pay-error')).toBeTruthy();
  });

  it('shows the translated failure when the server gives no message', () => {
    portal.payInvoice.and.returnValue(throwError(() => ({ status: 500 })));
    create([invoice()]);
    openDialog();
    component.payReference = 'CARD-1';
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
