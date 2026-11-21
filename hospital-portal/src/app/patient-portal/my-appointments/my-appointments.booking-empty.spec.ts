import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';

import { MyAppointmentsComponent } from './my-appointments.component';
import { PatientPortalService, SchedulingDepartment } from '../../services/patient-portal.service';

/**
 * QA r65 D2731: on a freshly set-up hospital a patient could not book at all.
 * The Service list was empty (no clinician attached to a department), then
 * every date said « Aucun créneau libre ce jour-là » (no slot published), and
 * nothing told the patient why.
 */
describe('MyAppointmentsComponent — a hospital with nothing to book says so (QA r65 D2731)', () => {
  let portal: jasmine.SpyObj<PatientPortalService>;
  let fixture: ComponentFixture<MyAppointmentsComponent>;
  let component: MyAppointmentsComponent;

  const hospitals = [
    { id: 'h-new', name: 'Clinique Test D', address: 'Rue 1', phoneNumber: '+226 25 30 00 00' },
    { id: 'h-nophone', name: 'Clinique Test E', address: 'Rue 2', phoneNumber: null },
  ];

  beforeEach(() => {
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', [
      'getMyAppointments',
      'getSchedulingHospitals',
      'getSchedulingDepartments',
      'getSchedulingProviders',
      'bookAppointment',
      'getOpenSlots',
      'getRescheduleSlots',
    ]);
    portal.getMyAppointments.and.returnValue(of([]));
    portal.getSchedulingHospitals.and.returnValue(of(hospitals));
    portal.getSchedulingDepartments.and.returnValue(of([]));
    portal.getSchedulingProviders.and.returnValue(of([]));
    portal.getOpenSlots.and.returnValue(of([]));
    portal.getRescheduleSlots.and.returnValue(of([]));

    TestBed.configureTestingModule({
      imports: [MyAppointmentsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: portal },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: () => null } } } },
      ],
    });
    fixture = TestBed.createComponent(MyAppointmentsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component.openBookingForm();
    fixture.detectChanges();
  });

  afterEach(() => TestBed.resetTestingModule());

  const el = (id: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(`[data-testid="${id}"]`);

  function chooseHospital(id: string): void {
    component.selectedHospitalId = id;
    component.onHospitalChange();
    fixture.detectChanges();
  }

  it('says nothing before a hospital is chosen', () => {
    expect(el('booking-not-offered')).toBeNull();
    expect(el('booking-department-no-slots')).toBeNull();
  });

  it('an empty Service list is explained, with the hospital’s number to call', () => {
    chooseHospital('h-new');

    const notice = el('booking-not-offered')!;
    expect(notice.getAttribute('role')).toBe('status');
    expect(notice.textContent).toContain('PLATFORM_SETUP.BOOKING.NOT_OFFERED');
    expect(notice.textContent).toContain('PLATFORM_SETUP.BOOKING.CALL');
    expect(component.selectedHospitalPhone()).toBe('+226 25 30 00 00');
  });

  it('a hospital with no phone number on file says to contact it, never « appelez le » with nothing', () => {
    chooseHospital('h-nophone');

    const notice = el('booking-not-offered')!;
    expect(notice.textContent).toContain('PLATFORM_SETUP.BOOKING.CONTACT');
    expect(notice.textContent).not.toContain('PLATFORM_SETUP.BOOKING.CALL');
  });

  it('a hospital that offers services shows no notice', () => {
    portal.getSchedulingDepartments.and.returnValue(
      of([{ id: 'd1', name: 'Médecine générale', hasOpenSlots: true }]),
    );
    chooseHospital('h-new');

    expect(el('booking-not-offered')).toBeNull();
    expect(component.departments().length).toBe(1);
  });

  it('a failed request is said as a failure, never as « not offered »', () => {
    portal.getSchedulingDepartments.and.returnValue(throwError(() => new Error('offline')));
    chooseHospital('h-new');

    expect(el('booking-not-offered')).toBeNull();
    expect(el('booking-departments-failed')!.getAttribute('role')).toBe('alert');
  });

  it('nothing is said while the answer is still on its way, or about a hospital the patient left', () => {
    const slow = new Subject<SchedulingDepartment[]>();
    portal.getSchedulingDepartments.and.returnValues(
      slow,
      of([{ id: 'd1', name: 'Médecine générale', hasOpenSlots: true }]),
    );
    chooseHospital('h-new');
    expect(el('booking-not-offered')).toBeNull(); // still loading

    chooseHospital('h-nophone');
    slow.next([]); // the first hospital answers late, with nothing to book
    fixture.detectChanges();

    expect(el('booking-not-offered')).toBeNull();
    expect(component.departments().length).toBe(1);
  });

  it('a service with no published slot says so at once, before the patient tries date after date', () => {
    portal.getSchedulingDepartments.and.returnValue(
      of([
        { id: 'd1', name: 'Médecine générale', hasOpenSlots: false },
        { id: 'd2', name: 'Maternité', hasOpenSlots: true },
      ]),
    );
    chooseHospital('h-new');

    component.selectedDepartmentId = 'd1';
    component.onDepartmentChange();
    fixture.detectChanges();
    expect(el('booking-department-no-slots')!.textContent).toContain(
      'PLATFORM_SETUP.BOOKING.NO_SLOTS_PUBLISHED',
    );

    component.selectedDepartmentId = 'd2';
    component.onDepartmentChange();
    fixture.detectChanges();
    expect(el('booking-department-no-slots')).toBeNull();
  });

  it('an older server that does not say whether a service has slots raises no notice', () => {
    portal.getSchedulingDepartments.and.returnValue(of([{ id: 'd1', name: 'Médecine générale' }]));
    chooseHospital('h-new');
    component.selectedDepartmentId = 'd1';
    component.onDepartmentChange();
    fixture.detectChanges();

    expect(el('booking-department-no-slots')).toBeNull();
  });

  it('closing the form forgets the state, so the notice does not greet the next opening', () => {
    chooseHospital('h-new');
    expect(el('booking-not-offered')).not.toBeNull();

    component.closeBookingForm();
    component.openBookingForm();
    fixture.detectChanges();

    expect(component.departmentsState()).toBe('idle');
    expect(el('booking-not-offered')).toBeNull();
  });
});
