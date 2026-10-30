import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

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
    // QA D1060: no end time is computed or sent; the server sets it from the slot rules.
    expect(component.form.endTime).toBeUndefined();
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

  it('QA D947: ArrowDown then Enter picks a suggestion from the keyboard', () => {
    const component = create({});
    const awa = { id: 'p-awa', firstName: 'Awa', lastName: 'Traoré' } as PatientResponse;
    const moumouni = {
      id: 'p-mou',
      firstName: 'Moumouni',
      lastName: 'Sawadogo',
    } as PatientResponse;
    component.patientSuggestions.set([awa, moumouni]);
    component.patientDropdownOpen.set(true);

    component.onPatientKeydown(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    component.onPatientKeydown(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    expect(component.patientActive()).toBe(1);
    component.onPatientKeydown(new KeyboardEvent('keydown', { key: 'Enter' }));

    expect(component.form.patientId).toBe('p-mou');
    expect(component.patientDropdownOpen()).toBeFalse();
  });

  it('QA D947 review round 11: a new list drops the highlight, so Tab never picks a stranger', fakeAsync(() => {
    const component = create({});
    const awa = { id: 'p-awa', firstName: 'Awa', lastName: 'Traoré' } as PatientResponse;
    const moumouni = {
      id: 'p-mou',
      firstName: 'Moumouni',
      lastName: 'Sawadogo',
    } as PatientResponse;
    const issa = { id: 'p-issa', firstName: 'Issa', lastName: 'Ouédraogo' } as PatientResponse;
    component.patientSuggestions.set([awa, moumouni]);
    component.patientDropdownOpen.set(true);
    component.onPatientKeydown(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    component.onPatientKeydown(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    expect(component.patientActive()).toBe(1);

    // She keeps typing: the list is replaced.
    patients.list.and.returnValue(of([issa, awa]));
    component.onPatientQueryChange('Oued');
    tick(250);
    expect(component.patientActive()).toBe(-1);

    component.onPatientKeydown(new KeyboardEvent('keydown', { key: 'Tab' }));
    expect(component.form.patientId).toBeUndefined();
  }));

  it('QA D984: a refused date names the field and its reason, not the generic text', () => {
    const component = create({});
    const toast = TestBed.inject(ToastService) as jasmine.SpyObj<ToastService>;
    const appointments = TestBed.inject(AppointmentService) as jasmine.SpyObj<AppointmentService>;
    appointments.create.and.returnValue(
      throwError(() => ({
        status: 400,
        error: {
          error: 'Validation Failed',
          message: 'La demande contient des erreurs de validation',
          fieldErrors: { appointmentDate: 'doit être une date dans le présent ou le futur' },
        },
      })),
    );
    component.form.appointmentDate = '2020-01-01';
    component.form.startTime = '09:00';
    component.form.endTime = '09:30';
    component.form.patientId = 'p-1';
    component.form.staffId = 's-1';

    component.submit();

    // The labelled field reason ("{{field}}: {{reason}}"); the test loads no
    // translations, so the key itself comes back.
    const shown = toast.error.calls.mostRecent().args[0] as string;
    expect(shown).toBe('FIELD_LIMITS.FIELD_REASON');
    expect(shown).not.toBe('La demande contient des erreurs de validation');
  });

  it('QA D985 review round 14: picking another patient drops the recall; the same one keeps it', () => {
    const component = create({
      recallId: '11111111-2222-3333-4444-555555555555',
      patientId: '11111111-2222-3333-4444-555555555555',
    });
    expect(component.form.recallId).toBe('11111111-2222-3333-4444-555555555555');
    component.selectPatient({
      id: '11111111-2222-3333-4444-555555555555',
      firstName: 'Awa',
    } as PatientResponse);
    expect(component.form.recallId).toBe('11111111-2222-3333-4444-555555555555');
    component.selectPatient({ id: 'p-other', firstName: 'Issa' } as PatientResponse);
    expect(component.form.recallId).toBeUndefined();
  });

  it('QA D985: a refused recall is dropped, so the next submit books without it', () => {
    const component = create({ recallId: '11111111-2222-3333-4444-555555555555' });
    expect(component.form.recallId).toBe('11111111-2222-3333-4444-555555555555');
    const toast = TestBed.inject(ToastService) as jasmine.SpyObj<ToastService>;
    const appointments = TestBed.inject(AppointmentService) as jasmine.SpyObj<AppointmentService>;
    appointments.create.and.returnValue(
      throwError(() => ({
        status: 400,
        error: {
          error: 'Bad Request',
          message:
            'Ce rappel n’est plus ouvert. Validez de nouveau pour prendre le rendez-vous sans lui.',
          field: 'recallId',
          fieldErrors: { recallId: 'Ce rappel n’est plus ouvert.' },
        },
      })),
    );
    component.form.appointmentDate = '2099-01-01';
    component.form.startTime = '09:00';
    component.form.endTime = '09:30';
    component.form.patientId = 'p-1';
    component.form.staffId = 's-1';

    component.submit();

    expect(component.form.recallId).toBeUndefined();
    expect(toast.error.calls.mostRecent().args[0]).toContain('Ce rappel n’est plus ouvert');

    // Any other refusal keeps the recall the desk booked from.
    component.form.recallId = '11111111-2222-3333-4444-555555555555';
    appointments.create.and.returnValue(
      throwError(() => ({ status: 400, error: { error: 'Bad Request', message: 'slot taken' } })),
    );
    component.submit();
    expect(component.form.recallId).toBe('11111111-2222-3333-4444-555555555555');
  });

  it('QA D1060: the calendar duration is not turned into an end time, and none is sent', () => {
    const component = create({ date: inDays(5), time: '08:20', duration: '240' });
    const appointments = TestBed.inject(AppointmentService) as jasmine.SpyObj<AppointmentService>;
    // A refusal keeps the test on the page (no navigation); the payload is what matters.
    appointments.create.and.returnValue(throwError(() => ({ status: 409, error: {} })));
    component.form.patientId = 'p-1';
    component.form.staffId = 's-1';

    component.submit();

    const payload = appointments.create.calls.mostRecent().args[0];
    expect(payload.startTime).toBe('08:20');
    expect('endTime' in payload).toBeFalse();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('#end')).toBeNull();
    expect(el.querySelector('[data-testid="appt-duration-note"]')).not.toBeNull();
  });

  it('QA D1072: a refused booking marks the missing fields and focuses the first', () => {
    const component = create({});
    document.body.appendChild(fixture.nativeElement);
    try {
      fixture.detectChanges();
      component.form.appointmentDate = inDays(3);
      component.submit();
      const el = fixture.nativeElement as HTMLElement;
      const start = el.querySelector<HTMLInputElement>('#start')!;
      expect(start.getAttribute('aria-invalid')).toBe('true');
      expect(el.querySelector('#start-invalid-msg')).not.toBeNull();
      expect(el.querySelector('#apptPatientSearch')?.getAttribute('aria-invalid')).toBe('true');
      expect(el.querySelector('#date')?.hasAttribute('aria-invalid')).toBeFalse();
      expect(document.activeElement).toBe(start);
    } finally {
      fixture.nativeElement.remove();
    }
  });

  it('QA D1036: the picked-patient chip shows the middle name', () => {
    const component = create({});
    component.selectedPatient.set({
      id: 'p-1',
      firstName: 'Pélagie',
      middleName: 'Wendkouni',
      lastName: 'Kiemdé',
    } as PatientResponse);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Pélagie Wendkouni Kiemdé',
    );
  });
});
