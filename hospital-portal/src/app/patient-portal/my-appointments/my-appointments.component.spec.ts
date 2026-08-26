import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MyAppointmentsComponent } from './my-appointments.component';
import { PatientPortalService, PortalAppointment } from '../../services/patient-portal.service';

describe('MyAppointmentsComponent', () => {
  let component: MyAppointmentsComponent;
  let fixture: ComponentFixture<MyAppointmentsComponent>;
  let portalService: jasmine.SpyObj<PatientPortalService>;

  // Mutable so a deep-link test can set the params and re-run ngOnInit.
  // The component reads the SNAPSHOT, matching how it is actually entered:
  // one navigation from an emailed link, not a live param stream.
  let queryParams: Record<string, string> = {};

  beforeEach(async () => {
    const spy = jasmine.createSpyObj('PatientPortalService', [
      'getMyAppointments',
      'getSchedulingHospitals',
      'getSchedulingDepartments',
      'getSchedulingProviders',
      'bookAppointment',
    ]);
    queryParams = {};
    spy.getMyAppointments.and.returnValue(of([]));
    spy.getSchedulingHospitals.and.returnValue(of([]));
    spy.getSchedulingDepartments.and.returnValue(of([]));
    spy.getSchedulingProviders.and.returnValue(of([]));
    spy.bookAppointment.and.returnValue(of({} as PortalAppointment));

    await TestBed.configureTestingModule({
      imports: [MyAppointmentsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: spy },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: { get: (k: string) => queryParams[k] ?? null } },
          },
        },
      ],
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
      // No translations are loaded in TranslateModule.forRoot(); instant() returns the key.
      expect(component.bookingError()).toBe('PORTAL.APPOINTMENTS.BOOKING.MISSING_FIELDS');
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

  // ── Emailed deep links (appointment confirmation emails) ──────────
  //
  // AppointmentLinkGuard rewrites /appointments/cancel/{id} and
  // /appointments/reschedule/{id} onto this route as query params. Those
  // two URL shapes had no route at all before 2026-08-25, so every emailed
  // link landed on the wildcard route.

  describe('deep links', () => {
    const linked = (over: Partial<PortalAppointment> = {}): PortalAppointment =>
      ({
        id: 'appt-1',
        date: '2099-01-01',
        rawStartTime: '09:00',
        rawEndTime: '09:30',
        status: 'SCHEDULED',
        ...over,
      }) as PortalAppointment;

    it('opens the cancel modal for the appointment in the link', () => {
      portalService.getMyAppointments.and.returnValue(of([linked()]));
      queryParams = { cancel: 'appt-1' };

      component.ngOnInit();

      expect(component.cancelTarget()?.id).toBe('appt-1');
      expect(component.rescheduleTarget()).toBeNull();
    });

    it('opens the reschedule modal for the appointment in the link', () => {
      portalService.getMyAppointments.and.returnValue(of([linked()]));
      queryParams = { reschedule: 'appt-1' };

      component.ngOnInit();

      expect(component.rescheduleTarget()?.id).toBe('appt-1');
      expect(component.cancelTarget()).toBeNull();
    });

    it('opens nothing when no deep link is present', () => {
      portalService.getMyAppointments.and.returnValue(of([linked()]));

      component.ngOnInit();

      expect(component.cancelTarget()).toBeNull();
      expect(component.rescheduleTarget()).toBeNull();
    });

    it('shows the list rather than an error when the link is stale', () => {
      // An id that no longer resolves is expected, not exceptional: the
      // visit may already have been cancelled or rebooked since the email.
      portalService.getMyAppointments.and.returnValue(of([linked()]));
      queryParams = { cancel: 'long-gone' };

      component.ngOnInit();

      expect(component.cancelTarget()).toBeNull();
      expect(component.appointments().length).toBe(1);
    });

    it('will not open a modal for an appointment that cannot be modified', () => {
      // Otherwise an old email offers to cancel a visit the patient already
      // attended, and the backend refuses it on submit.
      portalService.getMyAppointments.and.returnValue(of([linked({ status: 'COMPLETED' })]));
      queryParams = { cancel: 'appt-1' };

      component.ngOnInit();

      expect(component.cancelTarget()).toBeNull();
    });
  });
});

/* ── P1 #9: cancel + reschedule ── */

describe('MyAppointmentsComponent — cancel and reschedule', () => {
  let fixture: ComponentFixture<MyAppointmentsComponent>;
  let component: MyAppointmentsComponent;
  let portal: jasmine.SpyObj<PatientPortalService>;

  function appt(overrides: Partial<PortalAppointment>): PortalAppointment {
    return {
      id: 'a-1',
      date: '2027-01-15',
      startTime: '9:00 AM',
      endTime: '9:30 AM',
      rawStartTime: '09:00',
      rawEndTime: '09:30',
      providerName: 'Dr. Traore',
      department: 'Cardiology',
      reason: 'Follow-up',
      status: 'SCHEDULED',
      location: 'CHU Bogodogo',
      preCheckedIn: false,
      ...overrides,
    };
  }

  beforeEach(async () => {
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', [
      'getMyAppointments',
      'cancelAppointment',
      'rescheduleAppointment',
      'getSchedulingHospitals',
      'getSchedulingDepartments',
      'getSchedulingProviders',
      'bookAppointment',
    ]);
    portal.getMyAppointments.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [MyAppointmentsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: portal },
        // No deep link in this block — the component reads the snapshot on
        // init, so it needs a route even when there is nothing on it.
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: { get: () => null } } },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyAppointmentsComponent);
    component = fixture.componentInstance;
  });

  it('buckets a cancelled future appointment into Past so it stays visible', () => {
    portal.getMyAppointments.and.returnValue(
      of([appt({ id: 'up-1', status: 'SCHEDULED' }), appt({ id: 'gone-1', status: 'CANCELLED' })]),
    );
    fixture.detectChanges();

    expect(component.upcoming().map((a) => a.id)).toEqual(['up-1']);
    expect(component.past().map((a) => a.id)).toEqual(['gone-1']);
  });

  it('offers cancel/reschedule only on actionable statuses', () => {
    expect(component.canModify(appt({ status: 'SCHEDULED' }))).toBeTrue();
    expect(component.canModify(appt({ status: 'CONFIRMED' }))).toBeTrue();
    expect(component.canModify(appt({ status: 'PENDING' }))).toBeTrue();
    expect(component.canModify(appt({ status: 'RESCHEDULED' }))).toBeTrue();
    expect(component.canModify(appt({ status: 'COMPLETED' }))).toBeFalse();
    expect(component.canModify(appt({ status: 'CANCELLED' }))).toBeFalse();
    expect(component.canModify(appt({ status: 'IN_PROGRESS' }))).toBeFalse();
  });

  it('submitCancel sends the id + reason and reloads on success', () => {
    fixture.detectChanges();
    portal.cancelAppointment.and.returnValue(of({}));
    portal.getMyAppointments.calls.reset();
    portal.getMyAppointments.and.returnValue(of([]));

    component.openCancel(appt({ id: 'a-9' }));
    component.cancelReason = 'Travel conflict';
    component.submitCancel();

    expect(portal.cancelAppointment).toHaveBeenCalledWith({
      appointmentId: 'a-9',
      reason: 'Travel conflict',
    });
    expect(component.cancelTarget()).toBeNull();
    expect(portal.getMyAppointments).toHaveBeenCalled();
  });

  it('submitCancel keeps the modal open and shows the error on failure', () => {
    fixture.detectChanges();
    portal.cancelAppointment.and.returnValue(
      throwError(() => ({ error: { message: 'Appointment is already cancelled' } })),
    );

    component.openCancel(appt({ id: 'a-9' }));
    component.submitCancel();

    expect(component.cancelTarget()).not.toBeNull();
    expect(component.cancelError()).toBe('Appointment is already cancelled');
  });

  it('openReschedule prefills the form from the raw HH:mm times', () => {
    component.openReschedule(
      appt({ date: '2027-02-01', rawStartTime: '10:00', rawEndTime: '10:30' }),
    );
    expect(component.rescheduleDate).toBe('2027-02-01');
    expect(component.rescheduleStartTime).toBe('10:00');
    expect(component.rescheduleEndTime).toBe('10:30');
  });

  it('submitReschedule rejects an end time not after the start time', () => {
    component.openReschedule(appt({}));
    component.rescheduleStartTime = '10:00';
    component.rescheduleEndTime = '09:30';
    component.submitReschedule();

    expect(portal.rescheduleAppointment).not.toHaveBeenCalled();
    expect(component.rescheduleError()).toBeTruthy();
  });

  it('submitReschedule rejects a past date', () => {
    component.openReschedule(appt({}));
    component.rescheduleDate = '2020-01-01';
    component.submitReschedule();

    expect(portal.rescheduleAppointment).not.toHaveBeenCalled();
    expect(component.rescheduleError()).toBeTruthy();
  });

  it('submitReschedule sends all four required fields and reloads on success', () => {
    fixture.detectChanges();
    portal.rescheduleAppointment.and.returnValue(of({}));
    portal.getMyAppointments.calls.reset();
    portal.getMyAppointments.and.returnValue(of([]));

    component.openReschedule(appt({ id: 'a-7' }));
    component.rescheduleDate = '2027-03-10';
    component.rescheduleStartTime = '14:00';
    component.rescheduleEndTime = '14:30';
    component.rescheduleReason = 'Afternoon works better';
    component.submitReschedule();

    expect(portal.rescheduleAppointment).toHaveBeenCalledWith({
      appointmentId: 'a-7',
      newDate: '2027-03-10',
      newStartTime: '14:00',
      newEndTime: '14:30',
      reason: 'Afternoon works better',
    });
    expect(component.rescheduleTarget()).toBeNull();
    expect(portal.getMyAppointments).toHaveBeenCalled();
  });

  it('renders cancel and reschedule buttons in an expanded upcoming card', () => {
    portal.getMyAppointments.and.returnValue(of([appt({ id: 'up-2', status: 'CONFIRMED' })]));
    fixture.detectChanges();
    component.toggleExpand('up-2');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="appt-cancel"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="appt-reschedule"]')).not.toBeNull();
  });
});
