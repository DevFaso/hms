import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { TreatmentPlansComponent } from './treatment-plans';
import { TreatmentPlanResponse, TreatmentPlanService } from '../services/treatment-plan.service';
import { HospitalService } from '../services/hospital.service';
import { StaffService } from '../services/staff.service';
import { PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';

describe('TreatmentPlansComponent (QA D890)', () => {
  let component: TreatmentPlansComponent;
  let plans: jasmine.SpyObj<TreatmentPlanService>;
  let toast: jasmine.SpyObj<ToastService>;

  beforeEach(() => {
    plans = jasmine.createSpyObj<TreatmentPlanService>('TreatmentPlanService', [
      'create',
      'update',
      'getAll',
    ]);
    plans.getAll.and.returnValue(of([]));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      providers: [
        { provide: TreatmentPlanService, useValue: plans },
        { provide: HospitalService, useValue: {} },
        { provide: StaffService, useValue: {} },
        { provide: PatientService, useValue: {} },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: { isSuperAdmin: () => false, globalView: () => false },
        },
        { provide: ActivatedRoute, useValue: {} },
        { provide: HospitalScopeUrlService, useValue: {} },
        {
          provide: TranslateService,
          useValue: {
            instant: (key: string, params?: Record<string, string>) =>
              params?.['fields'] !== undefined ? `${key}: ${params['fields']}` : key,
          },
        },
      ],
    });
    component = TestBed.runInInjectionContext(() => new TreatmentPlansComponent());
  });

  it('does not post an empty plan; the toast names the missing fields', () => {
    component.form = component.emptyForm();
    component.form.hospitalId = 'h1';

    component.submitForm();

    expect(plans.create).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith(
      'COMMON.MISSING_REQUIRED_FIELDS: PATIENTS.PATIENT, TREATMENT.AUTHOR_STAFF, TREATMENT.PROBLEM_STATEMENT',
    );
  });

  it('posts a complete plan', () => {
    plans.create.and.returnValue(of({ id: 'tp1' } as TreatmentPlanResponse));
    component.form = {
      ...component.emptyForm(),
      patientId: 'p1',
      hospitalId: 'h1',
      authorStaffId: 's1',
      problemStatement: 'Diabète déséquilibré',
    };

    component.submitForm();

    expect(plans.create).toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalled();
  });

  it("shows the server's refusal instead of the generic toast", () => {
    plans.create.and.returnValue(
      throwError(() => ({ status: 409, error: { message: 'Un plan actif existe déjà.' } })),
    );
    component.form = {
      ...component.emptyForm(),
      patientId: 'p1',
      hospitalId: 'h1',
      authorStaffId: 's1',
      problemStatement: 'Diabète déséquilibré',
    };

    component.submitForm();

    expect(toast.error).toHaveBeenCalledWith('Un plan actif existe déjà.');
  });
});
