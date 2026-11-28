import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { InsurerPaymentsComponent, proposeAllocation } from './insurer-payments.component';
import {
  ClaimLine,
  InsurerClaimsService,
  InsurerPayment,
} from '../../services/insurer-claims.service';
import { PayerCatalogueService } from '../../services/payer-catalogue.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';

/** Insurer claims PR 3 (QA D3983): « Règlements assureurs ». */
describe('InsurerPaymentsComponent (QA D3983)', () => {
  let fixture: ComponentFixture<InsurerPaymentsComponent>;
  let claims: jasmine.SpyObj<InsurerClaimsService>;
  let toast: jasmine.SpyObj<ToastService>;
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const openLine = (id: string, available: number): ClaimLine => ({
    id,
    batchId: 'batch-1',
    batchNumber: 'BORD-2026-0001',
    shareId: 'share-' + id,
    invoiceId: 'inv-' + id,
    invoiceItemId: 'item-' + id,
    invoiceNumber: 'F-' + id,
    patientName: 'Awa Sawadogo',
    actLabel: 'Radio thorax',
    claimedAmount: available,
    paidAmount: 0,
    rebilledAmount: 0,
    writtenOffAmount: 0,
    writeOffPendingAmount: 0,
    outstandingAmount: available,
    availableAmount: available,
  });

  const recorded: InsurerPayment = {
    id: 'pay-1',
    payerId: 'payer-1',
    payerName: 'Mutuelle Test A',
    amount: 9000,
    receivedDate: '2026-11-20',
    method: 'BANK_TRANSFER',
    reference: 'VIR-2026-0042',
    status: 'RECORDED',
    allocatedAmount: 6000,
    unallocatedAmount: 3000,
    recordedByMe: false,
  };

  function render(roles: string[], rows: InsurerPayment[] = [recorded]): void {
    claims = jasmine.createSpyObj<InsurerClaimsService>('InsurerClaimsService', [
      'listPayments',
      'openLines',
      'recordInsurerPayment',
      'allocate',
      'reversePayment',
    ]);
    claims.listPayments.and.returnValue(
      of({ content: rows, page: 0, size: 25, totalElements: rows.length, totalPages: 1 }),
    );
    claims.openLines.and.returnValue(of([openLine('1', 4000), openLine('2', 7800)]));
    claims.recordInsurerPayment.and.returnValue(of(recorded));
    claims.allocate.and.returnValue(of(recorded));
    claims.reversePayment.and.returnValue(of({ ...recorded, status: 'REVERSED' }));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    TestBed.configureTestingModule({
      imports: [InsurerPaymentsComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: InsurerClaimsService, useValue: claims },
        {
          provide: PayerCatalogueService,
          useValue: { list: () => of([{ id: 'payer-1', name: 'Mutuelle Test A', plans: [] }]) },
        },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
        { provide: ToastService, useValue: toast },
      ],
    });
    fixture = TestBed.createComponent(InsurerPaymentsComponent);
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
  }

  afterEach(() => {
    (fixture?.nativeElement as HTMLElement | undefined)?.remove();
  });

  it('the proposal fills the oldest lines first, each up to what it expects, and never more than the payment', () => {
    const lines = [openLine('1', 4000), openLine('2', 7800)];

    expect(proposeAllocation(lines, 9000)).toEqual({ '1': '4000', '2': '5000' });
    expect(proposeAllocation(lines, 3000)).toEqual({ '1': '3000' });
    expect(proposeAllocation(lines, 20000)).toEqual({ '1': '4000', '2': '7800' });
    expect(proposeAllocation(lines, 0)).toEqual({});
  });

  it('a payment is recorded with its amount, date, method, reference, allocations and one key', () => {
    render(['ROLE_ACCOUNTANT']);
    const component = fixture.componentInstance;
    component.startRecord();
    component.setPayer('payer-1');
    component.amount.set('9000');
    component.receivedDate.set('2026-11-20');
    component.reference.set('VIR-2026-0042');
    component.propose();
    fixture.detectChanges();

    expect(component.allocated()).toBe(9000);
    expect(component.unallocated()).toBe(0);
    component.setAllocation(openLine('2', 7800), '2000');
    expect(component.unallocated()).toBe(3000);
    fixture.detectChanges();
    expect(
      host().querySelector('[data-testid="insurer-payment-unallocated"]')!.textContent,
    ).toMatch(/INSURER_CLAIMS\.PAYMENTS\.UNALLOCATED_LINE/);

    component.save();

    const body = claims.recordInsurerPayment.calls.mostRecent().args[0];
    expect(body).toEqual(
      jasmine.objectContaining({
        payerId: 'payer-1',
        amount: 9000,
        receivedDate: '2026-11-20',
        method: 'BANK_TRANSFER',
        reference: 'VIR-2026-0042',
        allocations: [
          { claimLineId: '1', amount: 4000 },
          { claimLineId: '2', amount: 2000 },
        ],
      }),
    );
    expect(body.clientRequestId).toBeTruthy();
  });

  it('QA D1341 class: a refusal naming the reference marks that field with the server reason', () => {
    render(['ROLE_ACCOUNTANT']);
    claims.recordInsurerPayment.and.returnValue(
      throwError(() => fieldErrorsResponse({ reference: 'Cette référence est déjà utilisée.' })),
    );
    const component = fixture.componentInstance;
    component.startRecord();
    component.setPayer('payer-1');
    component.amount.set('9000');
    component.receivedDate.set('2026-11-20');
    component.reference.set('VIR-2026-0042');
    component.propose();
    fixture.detectChanges();

    component.save();
    fixture.detectChanges();

    expectFieldsMarked(fixture, {
      'insurer-payment-reference': 'Cette référence est déjà utilisée.',
    });
  });

  it('without an amount, a date or a reference nothing is sent; nor with a card number or an over-allocation', () => {
    render(['ROLE_HOSPITAL_ADMIN']);
    const component = fixture.componentInstance;
    component.startRecord();
    component.setPayer('payer-1');
    fixture.detectChanges();

    component.save();
    component.amount.set('9000');
    component.reference.set('4111 1111 1111 1111');
    component.save();
    component.reference.set('VIR-1');
    component.setAllocation(openLine('1', 4000), '4001');
    component.save();
    component.setAllocation(openLine('1', 4000), '4000');
    component.setAllocation(openLine('2', 7800), '7800');
    component.save();

    expect(claims.recordInsurerPayment).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledTimes(4);
  });

  it('what a payment still holds is allocated later to open lines of the same payer', () => {
    render(['ROLE_ACCOUNTANT']);
    const component = fixture.componentInstance;
    component.startAllocate(recorded);

    expect(claims.openLines).toHaveBeenCalledWith('payer-1');
    expect(component.budget()).toBe(3000);
    component.propose();
    component.save();

    expect(claims.allocate).toHaveBeenCalledWith('pay-1', [{ claimLineId: '1', amount: 3000 }]);
  });

  it('the payment form pressed empty marks the payer, the amount and the reference, and sends nothing', async () => {
    render(['ROLE_ACCOUNTANT']);
    host().querySelector<HTMLButtonElement>('[data-testid="insurer-payment-new"]')!.click();
    fixture.detectChanges();
    // ngModel writes its value a tick after the first render: the prefilled fields must show it first.
    await fixture.whenStable();
    fixture.detectChanges();

    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="insurer-payment-save"]',
      notMarked: ['insurer-payment-date', 'insurer-payment-method'],
      sent: claims.recordInsurerPayment,
    });
  });

  it('the reversal form pressed empty marks its reason and sends nothing', () => {
    render(['ROLE_ACCOUNTANT']);
    host()
      .querySelector<HTMLButtonElement>('[data-testid="insurer-payment-reverse-pay-1"]')!
      .click();
    fixture.detectChanges();

    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="insurer-payment-reverse-confirm"]',
      sent: claims.reversePayment,
    });
  });

  it('the billing specialist and the claims reviewer read only: no record, allocate or reverse', () => {
    render(['ROLE_BILLING_SPECIALIST', 'ROLE_CLAIMS_REVIEWER']);

    expect(host().querySelector('[data-testid="insurer-payment-new"]')).toBeNull();
    expect(host().querySelector('[data-testid="insurer-payment-allocate-pay-1"]')).toBeNull();
    expect(host().querySelector('[data-testid="insurer-payment-reverse-pay-1"]')).toBeNull();
    expect(host().querySelector('[data-testid="insurer-payments-read-only"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="insurer-payment-left-pay-1"]')!.textContent).toMatch(
      /3\s?000/,
    );
  });

  it('a payment is reversed with a reason, never by the person who recorded it', () => {
    render(['ROLE_ACCOUNTANT'], [recorded, { ...recorded, id: 'pay-2', recordedByMe: true }]);

    expect(host().querySelector('[data-testid="insurer-payment-reverse-pay-2"]')).toBeNull();
    host()
      .querySelector<HTMLButtonElement>('[data-testid="insurer-payment-reverse-pay-1"]')!
      .click();
    fixture.detectChanges();
    fixture.componentInstance.confirmReverse(recorded);
    expect(claims.reversePayment).not.toHaveBeenCalled();

    fixture.componentInstance.reversalReason.set('Erreur de saisie');
    fixture.componentInstance.confirmReverse(recorded);
    expect(claims.reversePayment).toHaveBeenCalledWith('pay-1', 'Erreur de saisie');
  });
});
