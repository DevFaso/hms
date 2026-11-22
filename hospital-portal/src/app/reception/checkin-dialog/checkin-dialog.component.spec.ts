import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { PAYMENT_RETRY_DELAY_MS } from '../../shared/payment-request-key';
import { CheckinDialogComponent, copayAmountMessageKey } from './checkin-dialog.component';
import { markServerFieldErrors, scopeOf } from '../../shared/forms/server-field-errors';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import {
  ReceptionService,
  ReceptionQueueItem,
  CheckInResponse,
  FrontDeskPatientSnapshot,
} from '../reception.service';
import { ToastService } from '../../core/toast.service';
import { defer, of, throwError } from 'rxjs';
import { HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { BillingService } from '../../services/billing.service';

describe('CheckinDialogComponent', () => {
  let component: CheckinDialogComponent;
  let fixture: ComponentFixture<CheckinDialogComponent>;
  let mockReceptionService: jasmine.SpyObj<ReceptionService>;
  let mockToastService: jasmine.SpyObj<ToastService>;

  const sampleQueueItem: ReceptionQueueItem = {
    appointmentId: 'appt-1',
    patientId: 'pat-1',
    patientName: 'Jane Doe',
    mrn: 'MRN-001',
    dateOfBirth: '1990-01-15',
    appointmentTime: '09:30',
    providerName: 'Dr. Smith',
    departmentName: 'General Medicine',
    appointmentReason: 'Follow-up',
    status: 'SCHEDULED',
    waitMinutes: 0,
    encounterId: null,
    hasInsuranceIssue: false,
    hasOutstandingBalance: false,
  };

  const sampleResponse: CheckInResponse = {
    appointmentId: 'appt-1',
    appointmentStatus: 'CHECKED_IN',
    encounterId: 'enc-1',
    encounterCode: 'ENC-20260410-AB12CD',
    encounterStatus: 'ARRIVED',
    patientId: 'pat-1',
    patientName: 'Jane Doe',
    arrivalTimestamp: '2026-04-10T09:30:00',
    chiefComplaint: null,
    message: 'Patient Jane Doe successfully checked in',
  };

  /** QA D3258 (r70): the patient's only policy, Mutuelle Test A at 75 %, and a 2 000 F consultation. */
  const snapshot = {
    patientId: 'pat-1',
    fullName: 'Jane Doe',
    mrn: 'MRN-001',
    dob: null,
    phone: null,
    email: null,
    address: null,
    insurance: {
      insuranceId: 'ins-1',
      hasActiveCoverage: true,
      primaryPayer: 'MUT-TEST-A',
      policyNumber: 'MTA-T1-R70-0001',
      expiresOn: '2027-12-31',
      expired: false,
      hasPrimary: true,
      verifiedAt: null,
      verifiedBy: null,
      payerName: 'Mutuelle Test A',
      planName: 'Formule Test Essentielle',
      effectiveFrom: '2026-01-01',
      coveragePercent: 75,
      consultationFee: 2000,
      consultationLabel: 'Consultation générale',
      suggestedPayerShare: 1500,
      suggestedCopay: 500,
    },
    billing: { openInvoiceCount: 0, totalBalanceDue: 0 },
    alerts: {},
  } as unknown as FrontDeskPatientSnapshot;

  beforeEach(async () => {
    mockReceptionService = jasmine.createSpyObj('ReceptionService', [
      'checkInPatient',
      'getPatientSnapshot',
    ]);
    mockReceptionService.getPatientSnapshot.and.returnValue(of(snapshot));
    mockToastService = jasmine.createSpyObj('ToastService', ['success', 'error', 'warning']);

    await TestBed.configureTestingModule({
      imports: [CheckinDialogComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: ReceptionService, useValue: mockReceptionService },
        { provide: ToastService, useValue: mockToastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CheckinDialogComponent);
    component = fixture.componentInstance;
    component.queueItem = sampleQueueItem;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('QA D3258 (r70): shows the payer, plan, policy and validity the desk verifies', () => {
    const host = fixture.nativeElement as HTMLElement;
    expect(mockReceptionService.getPatientSnapshot).toHaveBeenCalledWith('pat-1');
    expect(host.querySelector('[data-testid="checkin-coverage-payer"]')?.textContent).toContain(
      'Mutuelle Test A',
    );
    expect(host.querySelector('[data-testid="checkin-coverage-plan"]')?.textContent).toContain(
      'Formule Test Essentielle',
    );
    expect(host.querySelector('[data-testid="checkin-coverage-policy"]')?.textContent).toContain(
      'MTA-T1-R70-0001',
    );
    expect(
      host.querySelector('[data-testid="checkin-coverage-validity"]')?.textContent?.trim(),
    ).not.toBe('');
  });

  it('QA D3258 (r70): suggests the patient share of the consultation; the desk takes it in one press', () => {
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="checkin-suggested-copay"]')).not.toBeNull();
    expect(component.copayAmount()).toBeNull();
    host.querySelector<HTMLButtonElement>('[data-testid="checkin-use-suggested-copay"]')!.click();
    expect(component.copayAmount()).toBe(500);
  });

  it('should not allow submit when identity is not confirmed', () => {
    component.identityConfirmed.set(false);
    expect(component.canSubmit).toBeFalse();
  });

  it('should allow submit when identity is confirmed and appointment is present', () => {
    component.identityConfirmed.set(true);
    expect(component.canSubmit).toBeTrue();
  });

  it('should not allow submit when no appointment is selected', () => {
    component.queueItem = { ...sampleQueueItem, appointmentId: null };
    component.identityConfirmed.set(true);
    expect(component.canSubmit).toBeFalse();
  });

  it('should call checkInPatient on submit and emit checkedIn', () => {
    mockReceptionService.checkInPatient.and.returnValue(of(sampleResponse));

    component.identityConfirmed.set(true);
    component.chiefComplaint.set('Headache for 3 days');

    spyOn(component.checkedIn, 'emit');

    component.submit();

    expect(mockReceptionService.checkInPatient).toHaveBeenCalledWith(
      jasmine.objectContaining({
        appointmentId: 'appt-1',
        identityConfirmed: true,
        chiefComplaint: 'Headache for 3 days',
      }),
    );
    expect(component.checkedIn.emit).toHaveBeenCalledWith(sampleResponse);
    expect(mockToastService.success).toHaveBeenCalled();
    // D121: never the server's English prose.
    expect(mockToastService.success).not.toHaveBeenCalledWith(sampleResponse.message);
  });

  it('should show error toast on check-in failure', () => {
    mockReceptionService.checkInPatient.and.returnValue(
      throwError(() => ({ status: 409, error: { message: 'Appointment already checked in' } })),
    );

    component.identityConfirmed.set(true);
    component.submit();

    expect(mockToastService.error).toHaveBeenCalledWith('Appointment already checked in');
    expect(component.saving()).toBeFalse();
  });

  it('should emit dismissed on cancel', () => {
    spyOn(component.dismissed, 'emit');
    component.dismissed.emit();
    expect(component.dismissed.emit).toHaveBeenCalled();
  });

  describe('consent-to-treat capture (P3 #21)', () => {
    it('omits consent fields entirely when the checkbox is unticked', () => {
      mockReceptionService.checkInPatient.and.returnValue(of(sampleResponse));
      component.identityConfirmed.set(true);

      component.submit();

      const sent = mockReceptionService.checkInPatient.calls.mostRecent().args[0];
      expect('consentObtained' in sent).toBeFalse();
    });

    it('sends the consent block when ticked — recorded, never gating', () => {
      mockReceptionService.checkInPatient.and.returnValue(of(sampleResponse));
      component.identityConfirmed.set(true);
      component.consentObtained.set(true);
      component.consentSignedName.set('Jane Doe');

      component.submit();

      expect(mockReceptionService.checkInPatient).toHaveBeenCalledWith(
        jasmine.objectContaining({
          consentObtained: true,
          consentMethod: 'ELECTRONIC',
          consentSignedName: 'Jane Doe',
        }),
      );
    });

    it('consent is NOT required to submit (unlike identity confirmation)', () => {
      component.identityConfirmed.set(true);
      component.consentObtained.set(false);
      expect(component.canSubmit).toBeTrue();
    });

    it('QA D1591: shows the pre-check-in, ticks the consent given there and sends no second one', () => {
      component.queueItem = {
        ...sampleQueueItem,
        preCheckedIn: true,
        preCheckinTimestamp: '2026-11-12T08:15:00',
        preCheckInConsent: true,
      };
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('[data-testid="checkin-precheckin"]')).not.toBeNull();
      const box = el.querySelector<HTMLInputElement>('[data-testid="checkin-consent"]')!;
      expect(box.checked).toBeTrue();
      expect(box.disabled).toBeTrue();
      expect(el.querySelector('[data-testid="checkin-consent-precheckin"]')).not.toBeNull();

      mockReceptionService.checkInPatient.and.returnValue(of(sampleResponse));
      component.identityConfirmed.set(true);
      component.submit();
      const sent = mockReceptionService.checkInPatient.calls.mostRecent().args[0];
      expect('consentObtained' in sent).toBeFalse();
    });

    it('QA D1591: no pre-check-in, no line and the box stays free', () => {
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('[data-testid="checkin-precheckin"]')).toBeNull();
      const box = el.querySelector<HTMLInputElement>('[data-testid="checkin-consent"]')!;
      expect(box.checked).toBeFalse();
      expect(box.disabled).toBeFalse();
    });
  });

  it('QA D975: the copay goes in the chosen method with its reference', () => {
    mockReceptionService.checkInPatient.and.returnValue(of(sampleResponse));
    component.identityConfirmed.set(true);
    component.copayAmount.set(1500);
    component.copayMethod.set('MOBILE_MONEY');
    expect(component.canSubmit).toBeFalse();
    component.copayReference.set('OM-778812');
    // Billing plan PR 9: the operator is required too.
    expect(component.canSubmit).toBeFalse();
    component.copayOperator.set('ORANGE_MONEY');
    expect(component.canSubmit).toBeTrue();

    component.submit();

    const sent = mockReceptionService.checkInPatient.calls.mostRecent().args[0];
    expect(sent.copayMethod).toBe('MOBILE_MONEY');
    expect(sent.copayReference).toBe('OM-778812');
    expect(sent.copayMobileMoneyOperator).toBe('ORANGE_MONEY');
  });

  it('PR 11 (QA D2430): a copay carries one key per dialog; a check-in without a copay carries none', () => {
    mockReceptionService.checkInPatient.and.returnValue(of(sampleResponse));
    component.identityConfirmed.set(true);
    component.submit();
    expect('copayClientRequestId' in mockReceptionService.checkInPatient.calls.mostRecent().args[0])
      .withContext('no copay, no money, no key')
      .toBeFalse();

    component.copayAmount.set(1500);
    component.submit();
    const first =
      mockReceptionService.checkInPatient.calls.mostRecent().args[0].copayClientRequestId;
    expect(first).toEqual(jasmine.any(String));
    component.submit();
    expect(
      mockReceptionService.checkInPatient.calls.mostRecent().args[0].copayClientRequestId,
    ).toBe(first);
  });

  it('PR 11 (QA D2430): a lost answer on a copay check-in is resent once, then a warning, never « Échec »', fakeAsync(() => {
    let sends = 0;
    mockReceptionService.checkInPatient.and.returnValue(
      defer(() => {
        sends++;
        return throwError(() => new HttpErrorResponse({ status: 504 }));
      }),
    );
    component.identityConfirmed.set(true);
    component.copayAmount.set(1500);
    component.submit();
    tick(PAYMENT_RETRY_DELAY_MS);
    expect(sends).toBe(2);
    expect(mockToastService.warning).toHaveBeenCalledWith('PAYMENT_OUTCOME.CHECK_IN_UNKNOWN');
    expect(mockToastService.error).not.toHaveBeenCalled();
    expect(component.saving()).toBeFalse();
  }));

  it('D-15: a card number as the copay reference is refused on the field', () => {
    component.identityConfirmed.set(true);
    component.copayAmount.set(1500);
    component.copayMethod.set('CARD');
    component.copayReference.set('5555 5555 5555 4444');
    expect(component.refusals().map((r) => r.id)).toEqual(['copayReference']);
  });

  it('QA D975 + D1009: the copay receipt is saved under the server name, the encounter code only as fallback', () => {
    const billing = TestBed.inject(BillingService);
    const anchors: HTMLAnchorElement[] = [];
    const realCreate = document.createElement.bind(document);
    spyOn(document, 'createElement').and.callFake((tag: string) => {
      const el = realCreate(tag);
      if (tag === 'a') {
        spyOn(el as HTMLAnchorElement, 'click');
        anchors.push(el as HTMLAnchorElement);
      }
      return el;
    });
    spyOn(URL, 'createObjectURL').and.returnValue('blob:receipt');
    spyOn(URL, 'revokeObjectURL');
    const receipt = spyOn(billing, 'getReceiptPdf');
    const withCopay = { ...sampleResponse, copayInvoiceId: 'inv-1', copayPaymentId: 'pay-1' };
    mockReceptionService.checkInPatient.and.returnValue(of(withCopay));
    component.identityConfirmed.set(true);

    receipt.and.returnValue(
      of(
        new HttpResponse({
          body: new Blob(['%PDF']),
          headers: new HttpHeaders({
            'Content-Disposition': 'attachment; filename="REC-2026-0042.pdf"',
          }),
        }),
      ),
    );
    component.submit();
    expect(receipt).toHaveBeenCalledWith('inv-1', 'pay-1');
    expect(anchors.pop()?.download).toBe('REC-2026-0042.pdf');

    receipt.and.returnValue(of(new HttpResponse({ body: new Blob(['%PDF']) })));
    component.submit();
    expect(anchors.pop()?.download).toBe('recu-ENC-20260410-AB12CD.pdf');
  });

  it('QA D988: the check-in dialog is a modal dialog', () => {
    fixture.detectChanges();
    const dialog = (fixture.nativeElement as HTMLElement).querySelector('[role="dialog"]');
    expect(dialog?.getAttribute('aria-modal')).toBe('true');
  });

  describe('QA D1320 (round 29): every refused field at once, a bad copay never posted', () => {
    const page = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const msgOf = (id: string): string =>
      page().querySelector(`#${id}-invalid-msg`)?.textContent?.trim() ?? '';

    beforeEach(() => document.body.appendChild(page()));
    afterEach(() => page().remove());

    it('a negative copay and an unticked identity are both marked on one press, nothing is sent', () => {
      component.copayAmount.set(-200);
      fixture.detectChanges();
      page().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
      fixture.detectChanges();
      expect(mockReceptionService.checkInPatient).not.toHaveBeenCalled();
      expect(msgOf('checkinIdentityConfirmed')).toBe('RECEPTION.CONFIRM_IDENTITY_FIRST');
      expect(msgOf('copayAmount')).toBe('RECEPTION.COPAY_AMOUNT_NEGATIVE');
      expect(page().querySelector('#copayAmount')!.getAttribute('aria-invalid')).toBe('true');
    });

    it('a copay with cents is refused under the amount; a blank or 0 copay is fine', () => {
      component.identityConfirmed.set(true);
      component.copayAmount.set(1500.5);
      fixture.detectChanges();
      component.submit();
      expect(mockReceptionService.checkInPatient).not.toHaveBeenCalled();
      expect(msgOf('copayAmount')).toBe('BILLING.PAYMENT_WHOLE_AMOUNT');
      expect(copayAmountMessageKey(null)).toBeNull();
      expect(copayAmountMessageKey(0)).toBeNull();
      expect(copayAmountMessageKey(2000)).toBeNull();
    });

    it('a copay reference the server refuses (fieldErrors.copayReference) marks the reference', () => {
      component.copayAmount.set(1500);
      component.copayMethod.set('MOBILE_MONEY');
      fixture.detectChanges();
      const ref = page().querySelector<HTMLElement>('#copayReference')!;
      markServerFieldErrors(
        scopeOf(ref),
        new HttpErrorResponse({
          status: 409,
          error: { fieldErrors: { copayReference: 'Déjà utilisée', copayAmount: 'Trop' } },
        }),
      );
      expect(ref.getAttribute('aria-invalid')).toBe('true');
      expect(msgOf('copayReference')).toBe('Déjà utilisée');
      expect(msgOf('copayAmount')).toBe('Trop');
    });
  });

  it('QA D1444: the time of the visit reads without seconds, whichever page opened the dialog', () => {
    component.queueItem = { ...component.queueItem!, appointmentTime: '09:00:00' };
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('09:00');
    expect(text).not.toContain('09:00:00');
  });
});
