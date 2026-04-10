import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MyAppointmentsComponent } from './my-appointments.component';
import { PatientPortalService, PortalAppointment } from '../../services/patient-portal.service';

describe('MyAppointmentsComponent', () => {
  let component: MyAppointmentsComponent;
  let fixture: ComponentFixture<MyAppointmentsComponent>;
  let portalService: jasmine.SpyObj<PatientPortalService>;

  beforeEach(async () => {
    const spy = jasmine.createSpyObj('PatientPortalService', [
      'getMyAppointments',
      'getSchedulingHospitals',
      'getSchedulingDepartments',
      'getSchedulingProviders',
      'bookAppointment',
    ]);
    spy.getMyAppointments.and.returnValue(of([]));
    spy.getSchedulingHospitals.and.returnValue(of([]));
    spy.getSchedulingDepartments.and.returnValue(of([]));
    spy.getSchedulingProviders.and.returnValue(of([]));
    spy.bookAppointment.and.returnValue(of({} as PortalAppointment));

    await TestBed.configureTestingModule({
      imports: [MyAppointmentsComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: spy }],
    }).compileComponents();

    portalService = TestBed.inject(PatientPortalService) as jasmine.SpyObj<PatientPortalService>;
    fixture = TestBed.createComponent(MyAppointmentsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no appointments', () => {
    expect(component.appointments().length).toBe(0);
    expect(component.loading()).toBe(false);
  });

  it('should toggle expand', () => {
    component.toggleExpand('a1');
    expect(component.expandedId()).toBe('a1');
    component.toggleExpand('a1');
    expect(component.expandedId()).toBeNull();
  });

  // ── Booking form tests ────────────────────────────────────────

  describe('openBookingForm', () => {
    it('should set showBookingForm true and load hospitals', () => {
      portalService.getSchedulingHospitals.and.returnValue(
        of([{ id: 'h1', name: 'Hospital A', address: '123 St' }]),
      );
      component.openBookingForm();
      expect(component.showBookingForm()).toBeTrue();
      expect(portalService.getSchedulingHospitals).toHaveBeenCalled();
      expect(component.hospitals().length).toBe(1);
    });
  });

  describe('closeBookingForm', () => {
    it('should reset and hide booking form', () => {
      component.openBookingForm();
      component.selectedHospitalId = 'h1';
      component.closeBookingForm();
      expect(component.showBookingForm()).toBeFalse();
      expect(component.selectedHospitalId).toBe('');
    });
  });

  describe('onHospitalChange', () => {
    it('should load departments when hospital is selected', () => {
      portalService.getSchedulingDepartments.and.returnValue(
        of([{ id: 'd1', name: 'Cardiology' }]),
      );
      component.selectedHospitalId = 'h1';
      component.onHospitalChange();
      expect(portalService.getSchedulingDepartments).toHaveBeenCalledWith('h1');
      expect(component.departments().length).toBe(1);
    });

    it('should clear departments and providers when hospital cleared', () => {
      component.selectedHospitalId = '';
      component.onHospitalChange();
      expect(component.departments().length).toBe(0);
      expect(component.providers().length).toBe(0);
    });
  });

  describe('onDepartmentChange', () => {
    it('should load providers when department is selected', () => {
      portalService.getSchedulingProviders.and.returnValue(of([{ id: 's1', name: 'Dr. Smith' }]));
      component.selectedHospitalId = 'h1';
      component.selectedDepartmentId = 'd1';
      component.onDepartmentChange();
      expect(portalService.getSchedulingProviders).toHaveBeenCalledWith('h1', 'd1');
      expect(component.providers().length).toBe(1);
    });
  });

  describe('submitBooking', () => {
    it('should set error when required fields are missing', () => {
      component.submitBooking();
      expect(component.bookingError()).toBe('Please fill in all required fields.');
      expect(portalService.bookAppointment).not.toHaveBeenCalled();
    });

    it('should call bookAppointment and set success on valid submit', () => {
      portalService.bookAppointment.and.returnValue(of({} as PortalAppointment));
      component.selectedHospitalId = 'h1';
      component.selectedDepartmentId = 'd1';
      component.selectedDate = '2025-12-01';
      component.selectedTime = '09:00';
      component.appointmentReason = 'Checkup';

      component.submitBooking();
      expect(portalService.bookAppointment).toHaveBeenCalled();
      expect(component.bookingSuccess()).toBeTrue();
      expect(component.bookingLoading()).toBeFalse();
    });

    it('should set bookingError on failure', () => {
      portalService.bookAppointment.and.returnValue(
        throwError(() => ({ error: { message: 'Staff not available' } })),
      );
      component.selectedHospitalId = 'h1';
      component.selectedDepartmentId = 'd1';
      component.selectedDate = '2025-12-01';
      component.selectedTime = '09:00';

      component.submitBooking();
      expect(component.bookingError()).toBe('Staff not available');
      expect(component.bookingLoading()).toBeFalse();
    });
  });
});
