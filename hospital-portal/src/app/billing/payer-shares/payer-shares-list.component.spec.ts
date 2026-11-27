import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { PayerSharesListComponent } from './payer-shares-list.component';
import { BillingService, OpenPayerShare, PayerSharePage } from '../../services/billing.service';
import { PayerCatalogueService } from '../../services/payer-catalogue.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { expectEmptySubmitRefusal } from '../../testing/form-refusal-behaviour';

/** QA D2706 (r70): « Parts assureurs », the claims reviewer's list of the hospital's insurer shares. */
describe('PayerSharesListComponent (QA D2706)', () => {
  let fixture: ComponentFixture<PayerSharesListComponent>;
  let billing: jasmine.SpyObj<BillingService>;
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const open: OpenPayerShare = {
    id: 'share-1',
    invoiceId: 'inv-7',
    invoiceNumber: 'TWP-2026-000007',
    invoiceDate: '2026-11-22',
    patientName: 'Mariam R70 Démo-SawadogoT1',
    patientMrn: 'TWP8739',
    payerName: 'Mutuelle Test A',
    planName: 'Formule Test Essentielle',
    amount: 8500,
    status: 'OPEN',
    payerType: 'MUTUELLE',
    policyNumber: 'MTA-0042',
    subscriberName: 'Awa Sawadogo',
    coverageVerifiedAt: null,
    lines: [
      {
        itemId: 'line-1',
        description: 'Radio thorax',
        actCode: 'IMG-RTX',
        category: 'IMAGING',
        quantity: 1,
        grossAmount: 10000,
        payerShare: 8000,
        patientShare: 2000,
        preAuthorisationNumber: 'BON-117',
      },
    ],
  };

  const pageOf = (rows: OpenPayerShare[], totalPages = 1): PayerSharePage => ({
    content: rows,
    page: 0,
    size: 25,
    totalElements: rows.length,
    totalPages,
    totalAmount: rows.reduce((sum, r) => sum + r.amount, 0),
  });

  function render(roles: string[], rows: OpenPayerShare[] = [open], totalPages = 1): void {
    billing = jasmine.createSpyObj<BillingService>('BillingService', [
      'searchHospitalPayerShares',
      'decidePayerShare',
    ]);
    billing.searchHospitalPayerShares.and.returnValue(of(pageOf(rows, totalPages)));
    billing.decidePayerShare.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [PayerSharesListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: BillingService, useValue: billing },
        {
          provide: PayerCatalogueService,
          useValue: {
            list: () =>
              of([
                {
                  id: 'payer-1',
                  name: 'Mutuelle Test A',
                  plans: [{ id: 'plan-1', name: 'Formule Test Essentielle' }],
                },
              ]),
          },
        },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'info']),
        },
      ],
    });
    fixture = TestBed.createComponent(PayerSharesListComponent);
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
  }

  afterEach(() => {
    (fixture?.nativeElement as HTMLElement | undefined)?.remove();
  });

  it('lists the open and claimed shares with their invoice, patient and payer', () => {
    render(['ROLE_CLAIMS_REVIEWER']);

    expect(billing.searchHospitalPayerShares).toHaveBeenCalledWith(
      jasmine.objectContaining({
        statuses: ['OPEN', 'CLAIMED', 'PARTIALLY_PAID'],
        page: 0,
        size: 25,
      }),
    );
    const card = host().querySelector('[data-testid="payer-share-share-1"]')?.textContent ?? '';
    expect(card).toContain('Mutuelle Test A');
    expect(card).toContain('TWP-2026-000007');
    expect(card).toContain('TWP8739');
  });

  it('claims PR 1 (G1): each share shows its policy, subscriber and the acts claimed with their « bon »', () => {
    render(['ROLE_CLAIMS_REVIEWER']);

    expect(
      host().querySelector('[data-testid="payer-share-policy-share-1"]')?.textContent,
    ).toContain('MTA-0042');
    const acts =
      host().querySelector('[data-testid="payer-share-acts-share-1"]')?.textContent ?? '';
    expect(acts).toContain('IMG-RTX');
    expect(acts).toContain('Radio thorax');
    expect(acts).toContain('BON-117');
    expect(host().querySelector('[data-testid="payer-share-unverified-share-1"]')).not.toBeNull();
  });

  it('claims PR 1 (G2): the payer, plan and period filters reach the server from the first page', () => {
    render(['ROLE_ACCOUNTANT']);
    const component = fixture.componentInstance;
    component.setPayer('payer-1');
    component.planId.set('plan-1');
    component.fromDate.set('2026-11-01');
    component.toDate.set('2026-11-30');
    component.applyFilters();

    expect(billing.searchHospitalPayerShares).toHaveBeenCalledWith(
      jasmine.objectContaining({
        payerId: 'payer-1',
        planId: 'plan-1',
        fromDate: '2026-11-01',
        toDate: '2026-11-30',
        page: 0,
      }),
    );
  });

  it('claims PR 1 (G2): a period ending before it starts is refused and nothing is sent', () => {
    render(['ROLE_ACCOUNTANT']);
    billing.searchHospitalPayerShares.calls.reset();
    const component = fixture.componentInstance;
    component.fromDate.set('2026-11-30');
    component.toDate.set('2026-11-01');
    component.applyFilters();

    expect(billing.searchHospitalPayerShares).not.toHaveBeenCalled();
  });

  it('claims PR 1 (G2): the pager asks the server for the next page', () => {
    render(['ROLE_ACCOUNTANT'], [open], 3);
    host().querySelector<HTMLButtonElement>('[data-testid="payer-shares-next"]')!.click();

    expect(billing.searchHospitalPayerShares).toHaveBeenCalledWith(
      jasmine.objectContaining({ page: 1 }),
    );
  });

  it('insurer claims PR 3 (QA D3983): no « claimed » or « paid » button; an open share is only refused here', () => {
    render(['ROLE_CLAIMS_REVIEWER']);

    expect(host().querySelector('[data-testid="payer-share-claim-share-1"]')).toBeNull();
    expect(host().querySelector('[data-testid="payer-share-paid-share-1"]')).toBeNull();
    expect(host().querySelector('[data-testid="payer-share-reject-share-1"]')).not.toBeNull();
  });

  it('insurer claims PR 3 (QA D3983): a claimed share shows its bordereau, what was paid, the refusal reason and its date', () => {
    render(
      ['ROLE_CLAIMS_REVIEWER'],
      [
        {
          ...open,
          status: 'PARTIALLY_PAID',
          amount: 11800,
          paidAmount: 9000,
          rejectedAmount: 2800,
          openAmount: 0,
          rejectionReason: 'Plafond atteint',
          decidedAt: '2026-11-27T10:00:00',
          claimBatchId: 'batch-1',
          claimBatchNumber: 'BORD-2026-0001',
        },
      ],
    );

    const settlement = host().querySelector('[data-testid="payer-share-settlement-share-1"]')!;
    expect(settlement.textContent).toContain('BORD-2026-0001');
    expect(settlement.querySelector('a')!.getAttribute('href')).toBe('/billing/bordereaux/batch-1');
    expect(settlement.textContent).toMatch(/9\s?000/);
    expect(settlement.textContent).toMatch(/2\s?800/);
    expect(host().querySelector('[data-testid="payer-share-decided-share-1"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="payer-share-refusal-share-1"]')).not.toBeNull();
    // On a sent bordereau: refused line by line there, never whole from this list.
    expect(host().querySelector('[data-testid="payer-share-reject-share-1"]')).toBeNull();
  });

  it('a refusal needs the payer reason: the empty submit is refused on the field and nothing is sent', () => {
    render(['ROLE_CLAIMS_REVIEWER']);
    host().querySelector<HTMLButtonElement>('[data-testid="payer-share-reject-share-1"]')!.click();
    fixture.detectChanges();

    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="payer-share-reject-confirm"]',
      sent: billing.decidePayerShare,
    });
  });

  it('the super-admin reads only: no decision button', () => {
    render(['ROLE_SUPER_ADMIN']);

    expect(host().querySelector('[data-testid="payer-share-reject-share-1"]')).toBeNull();
  });

  it('review of #1353: another tab starts from its first page', () => {
    render(['ROLE_ACCOUNTANT'], [open], 3);
    host().querySelector<HTMLButtonElement>('[data-testid="payer-shares-next"]')!.click();
    host().querySelector<HTMLButtonElement>('[data-testid="payer-shares-view-closed"]')!.click();

    expect(billing.searchHospitalPayerShares.calls.mostRecent().args[0]).toEqual(
      jasmine.objectContaining({ statuses: ['PAID', 'REJECTED', 'WRITTEN_OFF'], page: 0 }),
    );
  });

  it('review of #1353: when the list shrinks under the page, the last page that exists is read', () => {
    render(['ROLE_ACCOUNTANT'], [open], 3);
    billing.searchHospitalPayerShares.and.returnValues(
      of({ content: [], page: 2, size: 25, totalElements: 26, totalPages: 2, totalAmount: 0 }),
      of(pageOf([open], 2)),
    );
    fixture.componentInstance.goTo(2);

    expect(billing.searchHospitalPayerShares.calls.mostRecent().args[0]).toEqual(
      jasmine.objectContaining({ page: 1 }),
    );
    expect(fixture.componentInstance.rows().length).toBe(1);
  });

  it('shows the paid and refused shares on demand', () => {
    render(['ROLE_ACCOUNTANT']);
    host().querySelector<HTMLButtonElement>('[data-testid="payer-shares-view-closed"]')!.click();

    expect(billing.searchHospitalPayerShares).toHaveBeenCalledWith(
      jasmine.objectContaining({ statuses: ['PAID', 'REJECTED', 'WRITTEN_OFF'] }),
    );
  });
});
