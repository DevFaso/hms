import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { ClaimBatchDetailComponent, wholeFrancs } from './claim-batch-detail.component';
import {
  ClaimBatch,
  ClaimLine,
  ClaimWriteOff,
  InsurerClaimsService,
} from '../../services/insurer-claims.service';
import { BillingService } from '../../services/billing.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';

/** Insurer claims PRs 2 to 4 (QA D3983): one bordereau, as a draft and once sent. */
describe('ClaimBatchDetailComponent (QA D3983)', () => {
  let fixture: ComponentFixture<ClaimBatchDetailComponent>;
  let claims: jasmine.SpyObj<InsurerClaimsService>;
  let toast: jasmine.SpyObj<ToastService>;
  let confirm: jasmine.SpyObj<ConfirmService>;
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const line: ClaimLine = {
    id: 'line-1',
    batchId: 'batch-1',
    shareId: 'share-1',
    invoiceId: 'inv-1',
    invoiceItemId: 'item-1',
    patientName: 'Awa Sawadogo',
    policyNumber: 'MTA-0042',
    invoiceNumber: 'F-0007',
    serviceDate: '2026-11-10',
    actLabel: 'Radio thorax',
    grossAmount: 14750,
    patientAmount: 2950,
    claimedAmount: 11800,
    paidAmount: 9000,
    rebilledAmount: 0,
    writtenOffAmount: 0,
    writeOffPendingAmount: 0,
    outstandingAmount: 2800,
    availableAmount: 2800,
    warnings: [],
    refusals: [],
  };

  const draft: ClaimBatch = {
    id: 'batch-1',
    number: null,
    payerId: 'payer-1',
    payerName: 'Mutuelle Test A',
    status: 'DRAFT',
    periodFrom: '2026-11-01',
    periodTo: '2026-11-30',
    lineCount: 1,
    grossTotal: 14750,
    patientTotal: 2950,
    claimedTotal: 11800,
    paidTotal: 0,
    rebilledTotal: 0,
    writtenOffTotal: 0,
    writeOffPendingTotal: 0,
    outstandingTotal: 11800,
    warningCount: 1,
    lines: [
      { ...line, paidAmount: 0, outstandingAmount: 11800, warnings: ['COVERAGE_NOT_VERIFIED'] },
    ],
  };

  const sent: ClaimBatch = {
    ...draft,
    number: 'BORD-2026-0001',
    status: 'PARTIALLY_SETTLED',
    submittedOn: '2026-12-02',
    paidTotal: 9000,
    outstandingTotal: 2800,
    warningCount: null,
    lines: [line],
  };

  function render(roles: string[], batch: ClaimBatch): void {
    claims = jasmine.createSpyObj<InsurerClaimsService>('InsurerClaimsService', [
      'getBatch',
      'submitBatch',
      'changeShares',
      'refuseLine',
      'requestWriteOff',
      'cancelBatch',
    ]);
    claims.getBatch.and.returnValue(of(batch));
    claims.submitBatch.and.returnValue(of(sent));
    claims.refuseLine.and.returnValue(of(sent));
    claims.changeShares.and.returnValue(of(draft));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    confirm = jasmine.createSpyObj<ConfirmService>('ConfirmService', ['ask']);
    confirm.ask.and.resolveTo(true);
    TestBed.configureTestingModule({
      imports: [ClaimBatchDetailComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'batch-1' }) } },
        },
        { provide: InsurerClaimsService, useValue: claims },
        {
          provide: BillingService,
          useValue: {
            searchHospitalPayerShares: () =>
              of({
                content: [
                  { id: 'share-2', invoiceId: 'inv-2', amount: 4000, status: 'OPEN' },
                  {
                    id: 'share-3',
                    invoiceId: 'inv-3',
                    amount: 1,
                    status: 'OPEN',
                    claimBatchId: 'x',
                  },
                ],
                page: 0,
                size: 100,
                totalElements: 2,
                totalPages: 1,
                totalAmount: 4001,
              }),
          },
        },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
        { provide: ToastService, useValue: toast },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
    fixture = TestBed.createComponent(ClaimBatchDetailComponent);
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
  }

  afterEach(() => {
    (fixture?.nativeElement as HTMLElement | undefined)?.remove();
  });

  it('a typed amount is a whole number of francs, more than 0', () => {
    expect(wholeFrancs('2 800')).toBe(2800);
    expect(wholeFrancs('2800.5')).toBeNull();
    expect(wholeFrancs('0')).toBeNull();
    expect(wholeFrancs('abc')).toBeNull();
  });

  it('a draft shows what to check, the shares still free, and is sent only after a confirmation', async () => {
    render(['ROLE_CLAIMS_REVIEWER'], draft);

    expect(host().querySelector('[data-testid="claim-batch-warnings"]')).not.toBeNull();
    // A share already on another bordereau is not offered.
    expect(host().querySelector('[data-testid="claim-share-add-share-2"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="claim-share-add-share-3"]')).toBeNull();

    fixture.componentInstance.submittedOn.set('2026-12-02');
    await fixture.componentInstance.submit();

    expect(confirm.ask).toHaveBeenCalled();
    expect(claims.submitBatch).toHaveBeenCalledWith('batch-1', {
      submittedOn: '2026-12-02',
      payerReference: null,
    });
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="claim-batch-title"]')!.textContent).toContain(
      'BORD-2026-0001',
    );
  });

  it('a draft sent without its date is refused on the date and nothing is sent', async () => {
    render(['ROLE_CLAIMS_REVIEWER'], draft);
    fixture.componentInstance.submittedOn.set('');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="claim-batch-submit"]',
      sent: claims.submitBatch,
    });
    expect(confirm.ask).not.toHaveBeenCalled();
  });

  it('QA D1341 class: a refusal naming the sending date marks that field with the server reason', async () => {
    render(['ROLE_CLAIMS_REVIEWER'], draft);
    claims.submitBatch.and.returnValue(
      throwError(() =>
        fieldErrorsResponse({ submittedOn: 'La date d’envoi ne peut pas être à venir.' }),
      ),
    );

    await fixture.componentInstance.submit();
    fixture.detectChanges();

    expectFieldsMarked(fixture, {
      'claim-submitted-on': 'La date d’envoi ne peut pas être à venir.',
    });
  });

  it('a declined confirmation sends nothing', async () => {
    render(['ROLE_CLAIMS_REVIEWER'], draft);
    confirm.ask.and.resolveTo(false);

    await fixture.componentInstance.submit();

    expect(claims.submitBatch).not.toHaveBeenCalled();
  });

  it('a refusal: only a patient-side reason offers billing the patient, and only to finance', () => {
    render(['ROLE_ACCOUNTANT'], sent);
    const component = fixture.componentInstance;
    component.start(line, 'refuse');

    component.setClass('PATIENT_SIDE');
    expect(component.resolutions()).toContain('REBILL_PATIENT');
    expect(component.resolution()).not.toBe('REBILL_PATIENT');
    component.setClass('PROVIDER_FAULT');
    expect(component.resolutions()).toEqual(['RESUBMIT', 'WRITE_OFF_REQUEST']);
    component.setClass('TARIFF_REDUCTION');
    expect(component.resolutions()).toEqual(['WRITE_OFF_REQUEST']);
    expect(component.writeOffReason()).toBe('CONTRACTUAL_ADJUSTMENT');
  });

  it('a billing specialist is never offered billing the patient', () => {
    render(['ROLE_BILLING_SPECIALIST'], sent);
    fixture.componentInstance.start(line, 'refuse');
    fixture.componentInstance.setClass('PATIENT_SIDE');

    expect(fixture.componentInstance.resolutions()).toEqual(['WRITE_OFF_REQUEST', 'RESUBMIT']);
  });

  it('re-billing 2 800 to the patient is confirmed once more, then sent with its class, reason and key', async () => {
    render(['ROLE_ACCOUNTANT'], sent);
    const component = fixture.componentInstance;
    component.start(line, 'refuse');
    component.setClass('PATIENT_SIDE');
    component.resolution.set('REBILL_PATIENT');
    component.amount.set('2800');
    component.reason.set('Plafond annuel atteint');

    await component.confirmAction(line);

    expect(confirm.ask).toHaveBeenCalled();
    const body = claims.refuseLine.calls.mostRecent().args[1];
    expect(claims.refuseLine.calls.mostRecent().args[0]).toBe('line-1');
    expect(body).toEqual(
      jasmine.objectContaining({
        amount: 2800,
        reasonClass: 'PATIENT_SIDE',
        resolution: 'REBILL_PATIENT',
        reason: 'Plafond annuel atteint',
        writeOffReason: null,
      }),
    );
    expect(body.clientRequestId).toBeTruthy();
  });

  it('an amount above what the line expects, a missing reason or a card number is refused before any call', async () => {
    render(['ROLE_ACCOUNTANT'], sent);
    const component = fixture.componentInstance;
    component.start(line, 'refuse');
    fixture.detectChanges();

    component.amount.set('2801');
    component.reason.set('Motif');
    await component.confirmAction(line);
    component.amount.set('2800');
    component.reason.set(' ');
    await component.confirmAction(line);
    component.reason.set('Carte 4111 1111 1111 1111');
    await component.confirmAction(line);

    expect(claims.refuseLine).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledTimes(3);
  });

  it('a write-off request needs the supporting document', async () => {
    render(['ROLE_CLAIMS_REVIEWER'], sent);
    const component = fixture.componentInstance;
    component.start(line, 'writeOff');
    fixture.detectChanges();
    component.amount.set('2800');
    component.reason.set('Accord amiable');

    await component.confirmAction(line);
    expect(claims.requestWriteOff).not.toHaveBeenCalled();

    claims.requestWriteOff.and.returnValue(of({ id: 'wo-1' } as ClaimWriteOff));
    component.supportingDocument.set('Lettre du 12/11');
    await component.confirmAction(line);
    expect(claims.requestWriteOff.calls.mostRecent().args[1]).toEqual(
      jasmine.objectContaining({
        amount: 2800,
        reasonCode: 'COMMERCIAL_WAIVER',
        supportingDocument: 'Lettre du 12/11',
      }),
    );
  });

  it('each form of a sent bordereau pressed empty marks its fields and sends nothing', async () => {
    render(['ROLE_ACCOUNTANT'], { ...sent, status: 'SUBMITTED' });
    claims.recordPayerReceipt = jasmine.createSpy('recordPayerReceipt');

    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="claim-receipt-save"]',
      sent: claims.recordPayerReceipt,
    });

    host().querySelector<HTMLButtonElement>('[data-testid="claim-batch-cancel"]')!.click();
    fixture.detectChanges();
    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="claim-batch-cancel-confirm"]',
      sent: claims.cancelBatch,
    });
    expect(confirm.ask).not.toHaveBeenCalled();

    host().querySelector<HTMLButtonElement>('[data-testid="claim-line-write-off-line-1"]')!.click();
    fixture.detectChanges();
    // ngModel writes its value a tick after the first render: the prefilled fields must show it first.
    await fixture.whenStable();
    fixture.detectChanges();
    // The reason and the supporting document are both named by the one press; the amount is filled.
    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="claim-action-confirm"]',
      notMarked: ['claim-action-amount'],
      sent: claims.requestWriteOff,
    });
  });

  it('cancelling a sent bordereau and removing an invoice from a draft are confirmed first', async () => {
    render(['ROLE_ACCOUNTANT'], { ...sent, status: 'SUBMITTED' });
    claims.cancelBatch.and.returnValue(of({ ...sent, status: 'CANCELLED' }));
    confirm.ask.and.resolveTo(false);
    fixture.componentInstance.cancelReason.set('Mauvais organisme');
    await fixture.componentInstance.confirmCancel();
    expect(claims.cancelBatch).not.toHaveBeenCalled();
    await fixture.componentInstance.removeShare(line);
    expect(claims.changeShares).not.toHaveBeenCalled();

    confirm.ask.and.resolveTo(true);
    await fixture.componentInstance.confirmCancel();
    expect(claims.cancelBatch).toHaveBeenCalledWith('batch-1', 'Mauvais organisme');
  });

  it('the super-admin reads only: no sending, no refusal, no export', () => {
    render(['ROLE_SUPER_ADMIN'], sent);

    expect(host().querySelector('[data-testid="claim-line-refuse-line-1"]')).toBeNull();
    expect(host().querySelector('[data-testid="claim-batch-pdf"]')).toBeNull();
    expect(host().querySelector('[data-testid="claim-batch-outstanding"]')!.textContent).toMatch(
      /2\s?800/,
    );
  });

  it('only finance may cancel a sent bordereau, and only while the payer has not answered', () => {
    render(['ROLE_BILLING_SPECIALIST'], { ...sent, status: 'SUBMITTED' });
    expect(host().querySelector('[data-testid="claim-batch-cancel"]')).toBeNull();
    (fixture.nativeElement as HTMLElement).remove();
    TestBed.resetTestingModule();

    render(['ROLE_ACCOUNTANT'], { ...sent, status: 'SUBMITTED' });
    expect(host().querySelector('[data-testid="claim-batch-cancel"]')).not.toBeNull();
    (fixture.nativeElement as HTMLElement).remove();
    TestBed.resetTestingModule();

    render(['ROLE_ACCOUNTANT'], sent);
    expect(host().querySelector('[data-testid="claim-batch-cancel"]')).toBeNull();
  });
});
