import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CheckinDialogComponent } from './checkin-dialog.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ReceptionService, ReceptionQueueItem, CheckInResponse } from '../reception.service';
import { ToastService } from '../../core/toast.service';
import { of, throwError } from 'rxjs';

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
        provideHttpClient(),
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
  });

  it('should show error toast on check-in failure', () => {
    mockReceptionService.checkInPatient.and.returnValue(
      throwError(() => ({ error: { message: 'Appointment already checked in' } })),
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
});
