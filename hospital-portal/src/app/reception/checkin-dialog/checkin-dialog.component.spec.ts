import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CheckinDialogComponent } from './checkin-dialog.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { ReceptionService, ReceptionQueueItem, CheckInResponse } from '../reception.service';
import { ToastService } from '../../core/toast.service';
import { of, throwError } from 'rxjs';
import { HttpHeaders, HttpResponse } from '@angular/common/http';
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

  beforeEach(async () => {
    mockReceptionService = jasmine.createSpyObj('ReceptionService', ['checkInPatient']);
    mockToastService = jasmine.createSpyObj('ToastService', ['success', 'error']);

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
  });

  it('QA D975: the copay goes in the chosen method with its reference', () => {
    mockReceptionService.checkInPatient.and.returnValue(of(sampleResponse));
    component.identityConfirmed.set(true);
    component.copayAmount.set(1500);
    component.copayMethod.set('MOBILE_MONEY');
    expect(component.canSubmit).toBeFalse();
    component.copayReference.set('OM-778812');
    expect(component.canSubmit).toBeTrue();

    component.submit();

    const sent = mockReceptionService.checkInPatient.calls.mostRecent().args[0];
    expect(sent.copayMethod).toBe('MOBILE_MONEY');
    expect(sent.copayReference).toBe('OM-778812');
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
});
