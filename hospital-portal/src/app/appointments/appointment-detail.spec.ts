import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { AppointmentDetailComponent } from './appointment-detail';
import { AppointmentService, AppointmentResponse } from '../services/appointment.service';
import { ReceptionService } from '../reception/reception.service';
import { ToastService } from '../core/toast.service';
import { AuthService } from '../auth/auth.service';

describe('AppointmentDetailComponent', () => {
  let component: AppointmentDetailComponent;
  let fixture: ComponentFixture<AppointmentDetailComponent>;
  let router: Router;

  const mockAppointment: AppointmentResponse = {
    id: 'appt-1',
    appointmentDate: '2026-04-14',
    startTime: '17:30:00',
    endTime: '18:00:00',
    status: 'CONFIRMED',
    patientId: 'p1',
    patientName: 'Patient001',
    patientEmail: 'patient@test.com',
    patientPhone: '555-1234',
    staffId: 's1',
    staffName: 'Dr. Smith',
    staffEmail: 'dr@test.com',
    hospitalId: 'h1',
    hospitalName: 'Test Hospital',
    hospitalAddress: '123 Main St',
    departmentId: 'd1',
    reason: 'Follow-up',
    notes: null,
    createdByName: 'Admin',
    createdAt: '2026-04-13T10:00:00',
    updatedAt: '2026-04-13T10:00:00',
  } as any;

  const mockAppointmentService = {
    getById: jasmine.createSpy('getById').and.returnValue(of(mockAppointment)),
    update: jasmine.createSpy('update').and.returnValue(of(mockAppointment)),
    updateStatus: jasmine.createSpy('updateStatus').and.returnValue(of(mockAppointment)),
  };

  const mockReceptionService = {
    checkInPatient: jasmine
      .createSpy('checkInPatient')
      .and.returnValue(of({ appointmentId: 'appt-1', appointmentStatus: 'CHECKED_IN' })),
  };

  const mockToastService = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  const mockAuthService = {
    getRoles: () => ['ROLE_RECEPTIONIST'],
    hasAnyRole: (r: string[]) => r.includes('ROLE_RECEPTIONIST'),
    getToken: () => 'fake-token',
    getUserProfile: () => ({
      id: 'u1',
      username: 'receptionist1',
      email: 'recep@test.com',
      roles: ['ROLE_RECEPTIONIST'],
      staffId: 's2',
      active: true,
    }),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppointmentDetailComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: () => 'appt-1' } } },
        },
        { provide: AppointmentService, useValue: mockAppointmentService },
        { provide: ReceptionService, useValue: mockReceptionService },
        { provide: ToastService, useValue: mockToastService },
        { provide: AuthService, useValue: mockAuthService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AppointmentDetailComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load appointment on init', () => {
    expect(mockAppointmentService.getById).toHaveBeenCalledWith('appt-1');
    expect(component.appointment()).toBeTruthy();
    expect(component.appointment()?.id).toBe('appt-1');
  });

  it('checkIn actually checks the patient in instead of just navigating', () => {
    // THE REGRESSION, and the spec that used to guard it read
    // "goToCheckIn should navigate to /reception" — it asserted the button
    // did nothing but change the URL, which is precisely what was wrong.
    // Check-in is what creates the Encounter(ARRIVED) the Patient Tracker
    // board is built from; without the API call the board stays empty
    // however many times the button is pressed.
    const navigate = spyOn(router, 'navigate');
    mockReceptionService.checkInPatient.calls.reset();
    mockAppointmentService.getById.calls.reset();

    component.checkIn();

    expect(mockReceptionService.checkInPatient).toHaveBeenCalledWith({
      appointmentId: 'appt-1',
    });
    expect(navigate).not.toHaveBeenCalled();
    // Re-read rather than patching locally — check-in also stamps
    // checkedInAt and creates the encounter.
    expect(mockAppointmentService.getById).toHaveBeenCalledWith('appt-1');
  });

  it('reschedule saves the appointment as SCHEDULED, not RESCHEDULED', () => {
    // Writing RESCHEDULED stranded the appointment: no action buttons on this
    // page, no check-in icon in the reception queue, and checkInPatient
    // rejects the status outright. Right date, right time, no way to act.
    mockAppointmentService.update.calls.reset();
    component.rescheduleDate = '2099-01-01';
    component.rescheduleStart = '09:00:00';
    component.rescheduleEnd = '09:30:00';

    component.submitReschedule();

    expect(mockAppointmentService.update).toHaveBeenCalled();
    const req = mockAppointmentService.update.calls.mostRecent().args[1];
    expect(req.status).toBe('SCHEDULED');
    expect(req.status).not.toBe('RESCHEDULED');
    expect(req.appointmentDate).toBe('2099-01-01');
  });

  it('markAsScheduled sends the SCHEDULE action for legacy stranded rows', () => {
    mockAppointmentService.updateStatus.calls.reset();
    component.markAsScheduled();
    expect(mockAppointmentService.updateStatus).toHaveBeenCalledWith('appt-1', 'SCHEDULE');
  });

  it('canUpdateStatus should be true for ROLE_RECEPTIONIST', () => {
    expect(component.canUpdateStatus).toBeTrue();
  });

  it('canCheckIn should be true for ROLE_RECEPTIONIST on a CONFIRMED appointment', () => {
    expect(component.canCheckIn).toBeTrue();
  });

  it('canCheckIn is false for a status the backend would refuse', () => {
    // The role check alone used to decide this, so the button appeared on
    // appointments that ReceptionServiceImpl.checkInPatient rejects with an
    // IllegalStateException. Drawing it just moves the failure one click
    // later, onto an error toast the receptionist can do nothing about.
    for (const status of ['RESCHEDULED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED'] as const) {
      component.appointment.set({ ...mockAppointment, status });
      expect(component.canCheckIn)
        .withContext(`check-in must not be offered for ${status}`)
        .toBeFalse();
    }
    component.appointment.set(mockAppointment);
  });

  it('time fields should include seconds for slice pipe to trim', () => {
    const appt = component.appointment();
    expect(appt?.startTime).toBe('17:30:00');
    expect(appt?.endTime).toBe('18:00:00');
    // The template uses | slice:0:5 to display 17:30 and 18:00
  });

  it('getStatusClass should return correct class for CONFIRMED', () => {
    expect(component.getStatusClass('CONFIRMED')).toBe('status-confirmed');
  });

  it('canCancel should be true for CONFIRMED status', () => {
    expect(component.canCancel).toBeTrue();
  });

  it('canReschedule should be true for CONFIRMED status', () => {
    expect(component.canReschedule).toBeTrue();
  });
});
