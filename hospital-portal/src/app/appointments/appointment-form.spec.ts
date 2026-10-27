import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { AppointmentFormComponent } from './appointment-form';
import { AppointmentService } from '../services/appointment.service';
import { PatientResponse, PatientService } from '../services/patient.service';
import { StaffService } from '../services/staff.service';
import { HospitalResponse, HospitalService } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { localDateString } from '../shared/date-utils';
import {
  EXPECTED_PICKER_SUBLINES,
  renderPickerSublines,
} from '../testing/patient-subline.fixtures';

/**
 * QA D810: the prenatal calendar's "Book" action opens the appointment form
 * prefilled. Without query parameters the form opens blank, as before.
 */
describe('AppointmentFormComponent — prefill from the prenatal calendar', () => {
  let patients: jasmine.SpyObj<PatientService>;
  let fixture: ComponentFixture<AppointmentFormComponent>;

  function create(params: Record<string, string>): AppointmentFormComponent {
    patients = jasmine.createSpyObj<PatientService>('PatientService', ['list', 'getById']);
    patients.list.and.returnValue(of([]));
    patients.getById.and.returnValue(
      of({ id: '11111111-2222-3333-4444-555555555555', firstName: 'Awa' } as PatientResponse),
    );
    const staff = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staff.list.and.returnValue(of([]));
    const hospitals = jasmine.createSpyObj<HospitalService>('HospitalService', [
      'getMyHospitalAsResponse',
      'list',
    ]);
    hospitals.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h-1', name: 'A' } as HospitalResponse),
    );
    hospitals.list.and.returnValue(of([]));

    TestBed.configureTestingModule({
      imports: [AppointmentFormComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(params) } },
        },
        {
          provide: AppointmentService,
          useValue: jasmine.createSpyObj('AppointmentService', ['create']),
        },
        { provide: PatientService, useValue: patients },
        { provide: StaffService, useValue: staff },
        { provide: HospitalService, useValue: hospitals },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        {
          provide: RoleContextService,
          // A midwife scoped to one hospital; the form writes the active
          // hospital back, so this stand-in keeps it writable.
          useValue: {
            permittedHospitalIds: ['h-1'],
            activeHospitalId: 'h-1',
            isReceptionist: () => false,
          },
        },
      ],
    });
    fixture = TestBed.createComponent(AppointmentFormComponent);
    const component = fixture.componentInstance;
    component.ngOnInit();
    return component;
  }

  function inDays(days: number): string {
    const d = new Date();
    d.setDate(d.getDate() + days);
    return localDateString(d);
  }

  it('fills the patient, the day, the time and the antenatal reason', () => {
    const day = inDays(20);
    const component = create({
      patientId: '11111111-2222-3333-4444-555555555555',
      date: day,
      time: '09:30',
      reason: 'Consultation prénatale (CPN) — 24 SA',
    });

    expect(patients.getById).toHaveBeenCalledWith('11111111-2222-3333-4444-555555555555');
    expect(component.selectedPatient()?.id).toBe('11111111-2222-3333-4444-555555555555');
    expect(component.form.patientId).toBe('11111111-2222-3333-4444-555555555555');
    expect(component.form.appointmentDate).toBe(day);
    expect(component.form.startTime).toBe('09:30');
    expect(component.form.endTime).toBe('10:00');
    expect(component.form.reason).toBe('Consultation prénatale (CPN) — 24 SA');
  });

  it('ignores a past day and malformed values', () => {
    const component = create({ patientId: 'not-a-uuid', date: '2020-01-01', time: '9h' });

    expect(patients.getById).not.toHaveBeenCalled();
    expect(component.form.appointmentDate).toBe('');
    expect(component.form.startTime).toBe('');
  });

  // QA D903/D912: the chip and the options led with the email, so a phone-only
  // patient read ' · MRN CHW1444'; both now give this hospital's MRN alone.
  it('names the patient by MRN in the chip and the options (QA D912)', () => {
    const component = create({});
    fixture.detectChanges();
    expect(renderPickerSublines(fixture, component)).toEqual(EXPECTED_PICKER_SUBLINES);
  });

  it('opens blank with no query parameters', () => {
    const component = create({});

    expect(patients.getById).not.toHaveBeenCalled();
    expect(component.form.appointmentDate).toBe('');
    expect(component.form.reason).toBe('');
    expect(component.selectedPatient()).toBeNull();
  });
});
