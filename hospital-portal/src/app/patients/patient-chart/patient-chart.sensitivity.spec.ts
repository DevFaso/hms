import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { PatientChartComponent } from './patient-chart.component';
import { PatientProblem, PatientService } from '../../services/patient.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';
import { roleContextStub } from '../../testing/role-context.stub';
import { AuthService } from '../../auth/auth.service';
import { SensitivityTag } from '../../shared/sensitivity/sensitivity-category';

/**
 * QA D2730 (review): the problem filter read a tag nothing wrote. The problem
 * list now shows a sensitive diagnosis as such, and the clinicians who read
 * the list set its category - on the row, and on a new diagnosis.
 */
describe('PatientChartComponent - the sensitive category of a diagnosis (QA D2730)', () => {
  let fixture: ComponentFixture<PatientChartComponent>;
  let component: PatientChartComponent;
  let patientService: jasmine.SpyObj<PatientService>;
  let host: HTMLElement;

  const b20: PatientProblem = {
    id: 'pb-1',
    patientId: 'p-1',
    hospitalId: 'h-1',
    problemDisplay: 'Maladie due au VIH',
    problemCode: 'B20',
    status: 'ACTIVE',
    sensitivityCategory: null,
    effectiveSensitivityCategory: 'HIV',
  };
  const asthma: PatientProblem = {
    id: 'pb-2',
    patientId: 'p-1',
    hospitalId: 'h-1',
    problemDisplay: 'Asthme',
    status: 'ACTIVE',
  };

  function setup(roles: string[]): void {
    patientService = jasmine.createSpyObj<PatientService>('PatientService', [
      'listAllergies',
      'listDiagnoses',
      'listChartUpdates',
      'addDiagnosis',
      'setDiagnosisSensitivity',
    ]);
    patientService.listAllergies.and.returnValue(of({ items: [], withheld: false }));
    patientService.listDiagnoses.and.returnValue(of({ items: [b20, asthma], withheld: false }));
    patientService.listChartUpdates.and.returnValue(
      of({ items: { content: [], totalElements: 0 }, withheld: false }),
    );
    patientService.setDiagnosisSensitivity.and.returnValue(of({ id: 'pb-2' } as SensitivityTag));
    patientService.addDiagnosis.and.returnValue(of(asthma));
    TestBed.configureTestingModule({
      imports: [PatientChartComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PatientService, useValue: patientService },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h-1', roles }),
        },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => true,
            getRoles: () => roles,
            getHospitalId: () => 'h-1',
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
    fixture = TestBed.createComponent(PatientChartComponent);
    component = fixture.componentInstance;
    component.patientId = 'p-1';
    fixture.detectChanges();
    component.setSection('problems');
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
  }

  it('marks a sensitive diagnosis on its row, and leaves an ordinary one unmarked', () => {
    setup(['ROLE_DOCTOR']);
    const badges = host.querySelectorAll('[data-testid="problem-sensitive"]');
    expect(badges.length).toBe(1);
    expect(badges[0].closest('tr')!.textContent).toContain('Maladie due au VIH');
  });

  it('a nurse, who cannot edit a diagnosis, sets its category from the row', () => {
    setup(['ROLE_NURSE']);
    expect(component.canEditProblems()).toBeFalse();
    const actions = host.querySelectorAll<HTMLButtonElement>('[data-testid="problem-sensitivity"]');
    expect(actions.length).toBe(2);

    actions[1].click();
    fixture.detectChanges();
    const select = host.querySelector<HTMLSelectElement>('#problem-sensitivity-select')!;
    expect(select).not.toBeNull();
    select.value = 'REPRODUCTIVE_HEALTH';
    select.dispatchEvent(new Event('change'));
    host.querySelector<HTMLButtonElement>('[data-testid="problem-sensitivity-save"]')!.click();
    fixture.detectChanges();

    expect(patientService.setDiagnosisSensitivity).toHaveBeenCalledOnceWith(
      'p-1',
      'pb-2',
      'REPRODUCTIVE_HEALTH',
    );
    expect(host.querySelector('[data-testid="problem-sensitivity-dialog"]')).toBeNull();
    // The list is read again, so the row shows what was saved.
    expect(patientService.listDiagnoses.calls.count()).toBeGreaterThan(1);
  });

  it('the dialog says which category the diagnosis takes from its visit, and none clears its own', () => {
    setup(['ROLE_MIDWIFE']);
    component.openProblemSensitivity({ ...b20, sensitivityCategory: 'HIV' });
    fixture.detectChanges();
    expect(host.querySelector<HTMLSelectElement>('#problem-sensitivity-select')!.value).toBe('HIV');
    expect(host.querySelector('[data-testid="problem-sensitivity-select-inherited"]')).toBeNull();

    component.closeProblemSensitivity();
    component.openProblemSensitivity(b20);
    fixture.detectChanges();
    expect(
      host.querySelector('[data-testid="problem-sensitivity-select-inherited"]'),
    ).not.toBeNull();
    component.submitProblemSensitivity();
    expect(patientService.setDiagnosisSensitivity).toHaveBeenCalledOnceWith('p-1', 'pb-1', null);
  });

  it('a doctor tags a new diagnosis in the form that records it', () => {
    setup(['ROLE_DOCTOR']);
    component.openAddProblem();
    fixture.detectChanges();
    const select = host.querySelector<HTMLSelectElement>('#problem-sensitivity-new')!;
    expect(select).not.toBeNull();
    select.value = 'BEHAVIOURAL_HEALTH';
    select.dispatchEvent(new Event('change'));
    component.problemForm.problemDisplay = 'Épisode dépressif';
    component.submitProblem();

    expect(patientService.addDiagnosis.calls.mostRecent().args[1].sensitivityCategory).toBe(
      'BEHAVIOURAL_HEALTH',
    );
  });

  it('a pharmacist reads the list and is offered no category control', () => {
    setup(['ROLE_PHARMACIST']);
    expect(host.querySelector('[data-testid="problem-sensitive"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="problem-sensitivity"]')).toBeNull();
  });

  // Policy 2026-11-20 (provisional): the release of a minor's sensitive result to a parent.
  const bornYearsAgo = (years: number): string => {
    const born = new Date();
    born.setFullYear(born.getFullYear() - years);
    born.setDate(born.getDate() - 1);
    return born.toISOString().slice(0, 10);
  };

  it('policy 2026-11-20: a doctor and a midwife are offered the family release for a patient under 18 only', () => {
    setup(['ROLE_DOCTOR']);
    expect(component.canReleaseToFamily()).withContext('no date of birth').toBeFalse();
    component.patientDateOfBirth = bornYearsAgo(15);
    expect(component.canReleaseToFamily()).withContext('15 years old').toBeTrue();
    component.patientDateOfBirth = bornYearsAgo(18);
    expect(component.canReleaseToFamily()).withContext('18 years old').toBeFalse();
    component.patientDateOfBirth = 'not a date';
    expect(component.canReleaseToFamily()).withContext('an unreadable date').toBeFalse();
  });

  it('policy 2026-11-20: a nurse and a pharmacist are not offered the family release', () => {
    setup(['ROLE_NURSE']);
    component.patientDateOfBirth = bornYearsAgo(15);
    expect(component.canReleaseToFamily()).toBeFalse();
  });

  it('policy 2026-11-20: a midwife is offered it', () => {
    setup(['ROLE_MIDWIFE']);
    component.patientDateOfBirth = bornYearsAgo(16);
    expect(component.canReleaseToFamily()).toBeTrue();
  });
});
