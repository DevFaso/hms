import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { InvoicePayerSharesComponent } from './invoice-payer-shares.component';
import {
  BillingInvoiceResponse,
  BillingService,
  InvoicePayerShare,
} from '../../services/billing.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';

/** Billing-money plan PR 7: "Part assureur / Part patient" and the payer's decisions on an invoice's detail. */
describe('InvoicePayerSharesComponent (billing-money plan PR 7)', () => {
  let fixture: ComponentFixture<InvoicePayerSharesComponent>;
  let billing: jasmine.SpyObj<BillingService>;
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  /** BC2: 8 500 billed, RAMU's share 5 600, the patient's 2 900. */
  function invoice(overrides: Partial<BillingInvoiceResponse> = {}): BillingInvoiceResponse {
    return {
      id: 'inv-bc2',
      invoiceNumber: 'CHWF-2026-000042',
      totalAmount: 8500,
      amountPaid: 0,
      balanceDue: 2900,
      insuranceCoverageAmount: 5600,
      patientResponsibilityAmount: 2900,
      status: 'SENT',
      ...overrides,
    } as BillingInvoiceResponse;
  }

  const open: InvoicePayerShare = {
    id: 'share-1',
    payerName: 'RAMU',
    planName: 'Formule Base',
    amount: 5600,
    coverageYear: 2026,
    status: 'OPEN',
  };

  function render(
    roles: string[],
    inv: BillingInvoiceResponse,
    rows: InvoicePayerShare[] = [],
  ): void {
    billing = jasmine.createSpyObj<BillingService>('BillingService', [
      'listPayerShares',
      'decidePayerShare',
    ]);
    billing.listPayerShares.and.returnValue(of(rows));
    TestBed.configureTestingModule({
      imports: [InvoicePayerSharesComponent, TranslateModule.forRoot()],
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
    fixture = TestBed.createComponent(InvoicePayerSharesComponent);
    fixture.componentRef.setInput('invoice', inv);
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
  }

  afterEach(() => {
    (fixture?.nativeElement as HTMLElement | undefined)?.remove();
  });

  it('shows the insurer share and the patient share, and the shares with their decisions', () => {
    render(['ROLE_BILLING_SPECIALIST'], invoice(), [open]);

    expect(host().querySelector('[data-testid="payer-share-amount"]')?.textContent).toContain('5');
    expect(host().querySelector('[data-testid="patient-share-amount"]')?.textContent).toContain(
      '2',
    );
    expect(host().querySelector('[data-testid="payer-share-share-1"]')?.textContent).toContain(
      'RAMU',
    );
    expect(host().querySelector('[data-testid="payer-share-reject-share-1"]')).not.toBeNull();
    expect(billing.listPayerShares).toHaveBeenCalledWith('inv-bc2');
  });

  it('QA D2437: a draft shows the estimated split, marked an estimate', () => {
    render(
      ['ROLE_BILLING_SPECIALIST'],
      invoice({
        status: 'DRAFT',
        insuranceCoverageAmount: 0,
        patientResponsibilityAmount: 8500,
        estimatedInsuranceCoverageAmount: 5600,
      }),
    );

    expect(fixture.componentInstance.isEstimate()).toBeTrue();
    expect(fixture.componentInstance.payerShare()).toBe(5600);
    expect(fixture.componentInstance.patientShare()).toBe(2900);
    expect(host().querySelector('[data-testid="payer-share-estimate"]')).not.toBeNull();
    expect(host().textContent).toContain('BILLING.PAYER_SHARES.PAYER_SHARE_ESTIMATED');
  });

  it('QA D2437: an issued invoice never shows an estimate', () => {
    render(['ROLE_BILLING_SPECIALIST'], invoice({ estimatedInsuranceCoverageAmount: 9999 }));
    expect(fixture.componentInstance.isEstimate()).toBeFalse();
    expect(fixture.componentInstance.payerShare()).toBe(5600);
    expect(host().querySelector('[data-testid="payer-share-estimate"]')).toBeNull();
  });

  it('shows nothing on an invoice no payer covers', () => {
    render(['ROLE_BILLING_SPECIALIST'], invoice({ insuranceCoverageAmount: 0 }));
    expect(host().querySelector('[data-testid="payer-shares"]')).toBeNull();
  });

  it('the front desk sees the split but neither the shares nor the decisions', () => {
    render(['ROLE_RECEPTIONIST'], invoice());
    expect(host().querySelector('[data-testid="payer-shares"]')).not.toBeNull();
    expect(billing.listPayerShares).not.toHaveBeenCalled();
    expect(host().querySelector('[data-testid^="payer-share-reject-"]')).toBeNull();
  });

  it("a rejected share shows the insurer's reason and offers no decision", () => {
    render(['ROLE_ACCOUNTANT'], invoice({ insuranceCoverageAmount: 0 }), [
      { ...open, status: 'REJECTED', rejectionReason: 'Bénéficiaire non à jour de cotisation' },
    ]);
    // The reason is the line's translation argument ("Refusé par l'assureur : {{reason}}").
    expect(host().querySelector('[data-testid="payer-share-reason-share-1"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="payer-share-reject-share-1"]')).toBeNull();
  });

  it('an empty rejection is refused on the reason and nothing is sent', () => {
    render(['ROLE_CLAIMS_REVIEWER'], invoice(), [open]);
    host().querySelector<HTMLButtonElement>('[data-testid="payer-share-reject-share-1"]')!.click();
    fixture.detectChanges();

    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="payer-share-reject-submit"]',
      sent: billing.decidePayerShare,
    });
    expect(marked).toEqual(['payer-share-reject-reason']);
  });

  it('a card number in the reason is refused before anything is sent (D-15)', () => {
    render(['ROLE_HOSPITAL_ADMIN'], invoice(), [open]);
    fixture.componentInstance.openReject(open);
    fixture.componentInstance.rejectReason.set('Carte 4111 1111 1111 1111');
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="payer-share-reject-submit"]')!.click();
    fixture.detectChanges();

    expect(billing.decidePayerShare).not.toHaveBeenCalled();
    expect(host().querySelector('#payer-share-reject-reason')?.getAttribute('aria-invalid')).toBe(
      'true',
    );
  });

  it('a rejection with its reason is sent, and the invoice is re-read', () => {
    render(['ROLE_BILLING_SPECIALIST'], invoice(), [open]);
    const rejected = { ...open, status: 'REJECTED' as const, rejectionReason: 'Hors délai' };
    billing.decidePayerShare.and.returnValue(of([rejected]));
    const changed = jasmine.createSpy('changed');
    fixture.componentInstance.changed.subscribe(changed);

    fixture.componentInstance.openReject(open);
    fixture.componentInstance.rejectReason.set('  Hors délai ');
    fixture.componentInstance.confirmReject();

    expect(billing.decidePayerShare).toHaveBeenCalledWith(
      'inv-bc2',
      'share-1',
      'reject',
      'Hors délai',
    );
    expect(changed).toHaveBeenCalled();
    expect(fixture.componentInstance.shares()).toEqual([rejected]);
  });

  it('the server refusal of the reason marks the field it names', () => {
    render(['ROLE_BILLING_SPECIALIST'], invoice(), [open]);
    billing.decidePayerShare.and.returnValue(
      throwError(() => fieldErrorsResponse({ reason: 'Le motif est limité à 500 caractères.' })),
    );
    fixture.componentInstance.openReject(open);
    fixture.detectChanges();
    fixture.componentInstance.rejectReason.set('x');
    fixture.componentInstance.confirmReject();
    fixture.detectChanges();

    expectFieldsMarked(fixture, {
      'payer-share-reject-reason': 'Le motif est limité à 500 caractères.',
    });
  });

  it('claim and paid are offered on an open share; only paid and reject once claimed', () => {
    render(['ROLE_ACCOUNTANT'], invoice(), [open]);
    billing.decidePayerShare.and.returnValue(of([{ ...open, status: 'CLAIMED' }]));
    expect(fixture.componentInstance.canClaim(open)).toBeTrue();
    fixture.componentInstance.decide(open, 'claim');
    expect(billing.decidePayerShare).toHaveBeenCalledWith('inv-bc2', 'share-1', 'claim', undefined);
    const claimed = { ...open, status: 'CLAIMED' as const };
    expect(fixture.componentInstance.canClaim(claimed)).toBeFalse();
    expect(fixture.componentInstance.canSettle(claimed)).toBeTrue();
    expect(fixture.componentInstance.canSettle({ ...open, status: 'PAID' })).toBeFalse();
  });
});
