import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { PatientChartComponent } from './patient-chart.component';
import { PatientAllergy, PatientService } from '../../services/patient.service';
import { HospitalRegistration, RegistrationService } from '../../services/registration.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';
import { roleContextStub } from '../../testing/role-context.stub';
import { AuthService } from '../../auth/auth.service';

/**
 * QA D527: DOCTOR_B (acting at hospital B) opened a hospital-A patient's chart
 * and was offered Add / Edit / Deactivate on the allergies; POST, PUT and
 * DELETE /patients/{id}/allergies refuse him because the patient is not
 * registered at B and the rows belong to A.
 */
describe('PatientChartComponent — allergy actions (QA D527)', () => {
  const HOSPITAL_A = 'hosp-a';
  const HOSPITAL_B = 'hosp-b';

  function allergy(hospitalId: string): PatientAllergy {
    return {
      id: 'al-' + hospitalId,
      allergenDisplay: 'Pénicilline',
      severity: 'SEVERE',
      active: true,
      hospitalId,
      hospitalName: hospitalId,
    } as unknown as PatientAllergy;
  }

  function setup(opts: {
    role: string;
    hospitalId: string;
    registrations: Partial<HospitalRegistration>[];
    allergies: PatientAllergy[];
  }) {
    const patientService = jasmine.createSpyObj<PatientService>('PatientService', [
      'listAllergies',
      'listDiagnoses',
      'listChartUpdates',
      'getDoctorTimeline',
    ]);
    patientService.listAllergies.and.returnValue(of({ items: opts.allergies, withheld: false }));
    patientService.listDiagnoses.and.returnValue(of({ items: [], withheld: false }));
    patientService.listChartUpdates.and.returnValue(
      of({ items: { content: [], totalElements: 0 }, withheld: false }),
    );
    const roleContext = roleContextStub({
      superAdmin: false,
      hospitalId: opts.hospitalId,
      roles: [opts.role],
    });
    const registrationService = jasmine.createSpyObj<RegistrationService>('RegistrationService', [
      'list',
    ]);
    registrationService.list.and.returnValue(of(opts.registrations as HospitalRegistration[]));

    TestBed.configureTestingModule({
      imports: [PatientChartComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PatientService, useValue: patientService },
        { provide: RegistrationService, useValue: registrationService },
        {
          provide: RoleContextService,
          useValue: roleContext,
        },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => true,
            getRoles: () => [opts.role],
            getHospitalId: () => opts.hospitalId,
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'info',
          ]),
        },
      ],
    });
    const fixture = TestBed.createComponent(PatientChartComponent);
    fixture.componentInstance.patientId = 'patient-a1';
    fixture.detectChanges();
    return { fixture, registrationService, roleContext };
  }

  function buttons(el: HTMLElement): string[] {
    return Array.from(el.querySelectorAll('button'))
      .map((b) => b.textContent?.trim() ?? '')
      .filter(Boolean);
  }

  it('DOCTOR_B on a hospital-A patient is offered no Add, Edit or Deactivate', () => {
    const { fixture } = setup({
      role: 'ROLE_DOCTOR',
      hospitalId: HOSPITAL_B,
      registrations: [],
      allergies: [allergy(HOSPITAL_A)],
    });
    const el = fixture.nativeElement as HTMLElement;

    expect(fixture.componentInstance.canAddAllergy()).toBeFalse();
    expect(buttons(el).some((t) => t.includes('CHART.ADD_ALLERGY'))).toBeFalse();
    expect(el.querySelector('.foreign-row .action-link')).toBeNull();
  });

  it('a doctor at the registering hospital keeps Add, Edit and Deactivate', () => {
    const { fixture } = setup({
      role: 'ROLE_DOCTOR',
      hospitalId: HOSPITAL_A,
      registrations: [{ id: 'r1', hospitalId: HOSPITAL_A, active: true }],
      allergies: [allergy(HOSPITAL_A)],
    });
    const el = fixture.nativeElement as HTMLElement;

    expect(fixture.componentInstance.canAddAllergy()).toBeTrue();
    expect(buttons(el).some((t) => t.includes('CHART.ADD_ALLERGY'))).toBeTrue();
    expect(el.querySelectorAll('tbody .action-link').length).toBe(2);
  });

  it('a pharmacist (who cannot read registrations) keeps the role gate alone', () => {
    const { fixture, registrationService } = setup({
      role: 'ROLE_PHARMACIST',
      hospitalId: HOSPITAL_A,
      registrations: [],
      allergies: [allergy(HOSPITAL_A)],
    });

    expect(registrationService.list).not.toHaveBeenCalled();
    expect(fixture.componentInstance.canAddAllergy()).toBeTrue();
  });

  it('a midwife reads allergies but is offered no write (the API refuses her)', () => {
    const { fixture } = setup({
      role: 'ROLE_MIDWIFE',
      hospitalId: HOSPITAL_A,
      registrations: [{ id: 'r1', hospitalId: HOSPITAL_A, active: true }],
      allergies: [allergy(HOSPITAL_A)],
    });

    expect(fixture.componentInstance.canAddAllergy()).toBeFalse();
    expect((fixture.nativeElement as HTMLElement).querySelector('tbody .action-link')).toBeNull();
  });
  it('asks about the acting hospital only, not the first page of every registration', () => {
    const { registrationService } = setup({
      role: 'ROLE_DOCTOR',
      hospitalId: HOSPITAL_A,
      registrations: [{ id: 'r1', hospitalId: HOSPITAL_A, active: true }],
      allergies: [],
    });

    expect(registrationService.list).toHaveBeenCalledOnceWith({
      patientId: 'patient-a1',
      hospitalId: HOSPITAL_A,
      active: true,
    });
  });

  it('re-asks on an access refresh, so Add follows the new answer', () => {
    const { fixture, registrationService } = setup({
      role: 'ROLE_DOCTOR',
      hospitalId: HOSPITAL_A,
      registrations: [],
      allergies: [],
    });
    expect(fixture.componentInstance.canAddAllergy()).toBeFalse();

    registrationService.list.and.returnValue(
      of([{ id: 'r1', hospitalId: HOSPITAL_A, active: true } as HospitalRegistration]),
    );
    // The host binds the token from the start; its first value is not a refresh.
    fixture.componentRef.setInput('refreshToken', 1);
    fixture.detectChanges();
    expect(registrationService.list).toHaveBeenCalledTimes(1);
    fixture.componentRef.setInput('refreshToken', 2);
    fixture.detectChanges();

    expect(registrationService.list).toHaveBeenCalledTimes(2);
    expect(fixture.componentInstance.canAddAllergy()).toBeTrue();
  });

  it('re-asks when the acting hospital changes, so Add is not carried over', () => {
    const { fixture, registrationService, roleContext } = setup({
      role: 'ROLE_DOCTOR',
      hospitalId: HOSPITAL_A,
      registrations: [{ id: 'r1', hospitalId: HOSPITAL_A, active: true }],
      allergies: [],
    });
    expect(fixture.componentInstance.canAddAllergy()).toBeTrue();

    registrationService.list.and.returnValue(of([]));
    roleContext.set({ hospitalId: HOSPITAL_B });
    fixture.detectChanges();

    expect(registrationService.list).toHaveBeenCalledWith({
      patientId: 'patient-a1',
      hospitalId: HOSPITAL_B,
      active: true,
    });
    expect(fixture.componentInstance.canAddAllergy()).toBeFalse();
  });
});
