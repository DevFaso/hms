import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { HttpHeaders, HttpResponse } from '@angular/common/http';
import { MyBillingComponent } from './my-billing.component';
import { PatientPortalService, PortalInvoice } from '../../services/patient-portal.service';
import { formatCfa } from '../../shared/i18n/app-locale';
import fr from '../../../assets/i18n/fr.json';

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
      'getMyInvoiceDetail',
      'getMyInvoicePdf',
      'getMyReceiptPdf',
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
    expect(text('.pay-error')).toBe(`Au plus ${formatCfa(150)} : ${formatCfa(150)} en attente`);
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
    expect(text('.pay-success')).toBe(
      `Paiement de ${formatCfa(50)} déclaré, en attente de confirmation`,
    );
    expect(portal.getMyInvoices).toHaveBeenCalledTimes(2);
  });

  it('QA D814: no "Pay now" on an invoice whose whole balance is already declared', () => {
    // The QA invoice -000024: everything owed is declared and awaits the cashier.
    create([
      invoice({ id: 'full', balance: 500, amount: 500, pending: 500 }),
      invoice({ id: 'part', balance: 500, amount: 500, pending: 200 }),
    ]);
    expect(host().querySelector('[data-testid="pay-btn-full"]')).toBeNull();
    expect(host().querySelector('[data-testid="fully-declared-full"]')).not.toBeNull();
    // Still owed until the cashier confirms, so it stays in the balance.
    expect(component.totalBalance()).toBe(1000);
    // A partly declared invoice still offers what is left.
    expect(host().querySelector('[data-testid="pay-btn-part"]')).not.toBeNull();
    // Nor can the dialog be opened another way with nothing to declare.
    component.openPayDialog(component.invoices()[0]);
    expect(component.payingInvoice()).toBeNull();
  });

  it('QA D815: the declared-payment confirmation names the currency, in the real French text', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    portal.payInvoice.and.returnValue(of(invoice({ balance: 1000 })));
    create([invoice({ balance: 1000, amount: 1000 })]);
    openDialog();
    component.payAmount = 500;
    component.payMethod = 'CASH';
    (host().querySelector('.pay-submit-btn') as HTMLElement).click();
    fixture.detectChanges();

    const success = text('.pay-success');
    expect(success).toContain(`paiement de ${formatCfa(500)}`);
    expect(success).toContain('F CFA');
  });

  it('QA D815: the over-declaration refusal names the currency once, in the real French text', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    create([invoice({ balance: 300, amount: 300, pending: 150 })]);
    openDialog();
    component.payAmount = 200;
    (host().querySelector('.pay-submit-btn') as HTMLElement).click();
    fixture.detectChanges();
    const error = text('.pay-error');
    expect(error).toContain(formatCfa(150));
    expect(error.match(/CFA/g)?.length).toBe(2);
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

  describe('invoice detail (QA D982)', () => {
    const detail = {
      invoice: invoice({ pending: 500 }),
      lines: [
        {
          id: 'l1',
          itemDescription: 'Consultation',
          quantity: 1,
          unitPrice: 3000,
          totalPrice: 3000,
        },
      ],
      payments: [
        { id: 'p1', amount: 1000, paymentMethod: 'CASH', status: 'CONFIRMED' },
        { id: 'p2', amount: 500, paymentMethod: 'MOBILE_MONEY', status: 'PENDING' },
      ],
      adjustments: [{ id: 'a1', type: 'WAIVER', amount: 500, invoiceItemDescription: 'NFS' }],
    };

    it('shows a declared payment on the card itself', () => {
      create([invoice({ pending: 500 })]);
      expect(host().querySelector('[data-testid="pending-inv1"]')).not.toBeNull();
    });

    it('opens on the lines and the payments with their state', () => {
      portal.getMyInvoiceDetail.and.returnValue(of(detail));
      create([invoice({ pending: 500 })]);
      (host().querySelector('[data-testid="detail-btn-inv1"]') as HTMLElement).click();
      fixture.detectChanges();

      expect(portal.getMyInvoiceDetail).toHaveBeenCalledWith('inv1');
      const panel = host().querySelector('[data-testid="invoice-detail-inv1"]') as HTMLElement;
      expect(panel.textContent).toContain('Consultation');
      // QA D974: why the total is lower than the lines.
      expect(panel.querySelector('[data-testid="portal-adjustments"]')?.textContent).toContain(
        'NFS',
      );
      expect(
        panel.querySelector('[data-testid="payment-p1"] [data-status="CONFIRMED"]'),
      ).not.toBeNull();
      expect(
        panel.querySelector('[data-testid="payment-p2"] [data-status="PENDING"]'),
      ).not.toBeNull();
      // A receipt only for money actually received.
      expect(panel.querySelector('[data-testid="receipt-p1"]')).not.toBeNull();
      expect(panel.querySelector('[data-testid="receipt-p2"]')).toBeNull();
      expect(
        host().querySelector('[data-testid="detail-btn-inv1"]')?.getAttribute('aria-expanded'),
      ).toBe('true');
    });

    it('downloads the invoice and a receipt under the server names', () => {
      portal.getMyInvoiceDetail.and.returnValue(of(detail));
      const pdf = (name: string) =>
        of(
          new HttpResponse({
            body: new Blob(['%PDF']),
            headers: new HttpHeaders({ 'Content-Disposition': `attachment; filename="${name}"` }),
          }),
        );
      portal.getMyInvoicePdf.and.returnValue(pdf('invoice-001.pdf'));
      portal.getMyReceiptPdf.and.returnValue(pdf('001-R1.pdf'));
      const saved: string[] = [];
      spyOn(URL, 'createObjectURL').and.returnValue('blob:x');
      spyOn(URL, 'revokeObjectURL');
      spyOn(HTMLAnchorElement.prototype, 'click').and.callFake(function (this: HTMLAnchorElement) {
        saved.push(this.download);
      });
      create([invoice()]);
      component.toggleDetail(component.invoices()[0]);
      fixture.detectChanges();
      (host().querySelector('[data-testid="invoice-pdf-inv1"]') as HTMLButtonElement).dispatchEvent(
        new Event('click'),
      );
      (host().querySelector('[data-testid="receipt-p1"]') as HTMLButtonElement).dispatchEvent(
        new Event('click'),
      );
      expect(saved).toEqual(['invoice-001.pdf', '001-R1.pdf']);
    });

    it('keeps the detail when a download fails', () => {
      portal.getMyInvoiceDetail.and.returnValue(of(detail));
      portal.getMyInvoicePdf.and.returnValue(throwError(() => new Error('down')));
      create([invoice()]);
      component.toggleDetail(component.invoices()[0]);
      component.downloadInvoice(component.invoices()[0]);
      fixture.detectChanges();
      expect(host().querySelector('[data-testid="download-error"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="invoice-detail-inv1"]')?.textContent).toContain(
        'Consultation',
      );
    });

    it('says so when the invoice cannot be loaded', () => {
      portal.getMyInvoiceDetail.and.returnValue(throwError(() => new Error('down')));
      create([invoice()]);
      component.toggleDetail(component.invoices()[0]);
      fixture.detectChanges();
      expect(
        host().querySelector('[data-testid="invoice-detail-inv1"] [role="alert"]'),
      ).not.toBeNull();
    });
  });

  describe('payment reference label (QA D1007)', () => {
    it('reads optional only for cash, required otherwise', () => {
      create([invoice()]);
      openDialog();
      component.payMethod = 'MOBILE_MONEY';
      fixture.detectChanges();
      const label = () => host().querySelector('label[for="payRef"]')?.textContent ?? '';
      const input = () => host().querySelector('#payRef') as HTMLInputElement;
      expect(label()).toContain('*');
      expect(input().required).toBeTrue();
      component.payMethod = 'CASH';
      fixture.detectChanges();
      expect(label()).not.toContain('*');
      expect(input().required).toBeFalse();
    });
  });
});
