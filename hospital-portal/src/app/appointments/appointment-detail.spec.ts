import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { AppointmentDetailComponent } from './appointment-detail';
import { AppointmentService, AppointmentResponse } from '../services/appointment.service';
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

  it('goToCheckIn should navigate to /reception', () => {
    const spy = spyOn(router, 'navigate');
    component.goToCheckIn();
    expect(spy).toHaveBeenCalledWith(['/reception']);
  });

  it('canUpdateStatus should be true for ROLE_RECEPTIONIST', () => {
    expect(component.canUpdateStatus).toBeTrue();
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
