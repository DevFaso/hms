import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ActivatedRoute } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { TreatmentPlansComponent } from './treatment-plans';
import {
  TreatmentPlanResponse,
  TreatmentPlanReview,
  TreatmentPlanService,
} from '../services/treatment-plan.service';
import { HospitalService } from '../services/hospital.service';
import { StaffService } from '../services/staff.service';
import { PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { AuthService } from '../auth/auth.service';
import { roleContextStub } from '../testing/role-context.stub';
import {
  EXPECTED_PICKER_SUBLINES,
  renderPickerSublines,
} from '../testing/patient-subline.fixtures';

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

describe('TreatmentPlansComponent patient picker (rendered, QA D912)', () => {
  // QA D903/D912: the chip showed the email, or an empty line for a
  // phone-only patient, instead of this hospital's MRN.
  it('names the patient by MRN in the chip and the options', () => {
    TestBed.configureTestingModule({
      imports: [TreatmentPlansComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        {
          provide: TreatmentPlanService,
          useValue: { getAll: () => of([]), create: () => of({}), update: () => of({}) },
        },
        {
          provide: HospitalService,
          useValue: { list: () => of([]), getMyHospitalAsResponse: () => of({ id: 'h1' }) },
        },
        { provide: StaffService, useValue: { list: () => of([]) } },
        { provide: PatientService, useValue: { list: () => of([]) } },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']),
        },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h1',
            roles: ['ROLE_DOCTOR'],
          }),
        },
        { provide: HospitalScopeUrlService, useValue: { applyUrlScopeSync: () => undefined } },
      ],
    });
    const fixture = TestBed.createComponent(TreatmentPlansComponent);
    fixture.detectChanges();
    fixture.componentInstance.openCreate();
    fixture.detectChanges();
    expect(renderPickerSublines(fixture, fixture.componentInstance)).toEqual(
      EXPECTED_PICKER_SUBLINES,
    );
  });
});

describe('TreatmentPlansComponent approval (QA D1127)', () => {
  let component: TreatmentPlansComponent;
  let plans: jasmine.SpyObj<TreatmentPlanService>;
  let toast: jasmine.SpyObj<ToastService>;
  const plan = (over: Partial<TreatmentPlanResponse>): TreatmentPlanResponse =>
    ({
      id: 'tp1',
      status: 'IN_REVIEW',
      authorStaffId: 's-author',
      ...over,
    }) as TreatmentPlanResponse;

  beforeEach(() => {
    plans = jasmine.createSpyObj<TreatmentPlanService>('TreatmentPlanService', [
      'create',
      'update',
      'getAll',
      'addReview',
    ]);
    plans.getAll.and.returnValue(of([]));
    plans.addReview.and.returnValue(of({} as TreatmentPlanReview));
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
        {
          provide: AuthService,
          useValue: {
            getUserProfile: () => ({ id: 'u-colleague', staffId: 's-colleague' }),
            getUserId: () => 'u-colleague',
          },
        },
        { provide: ActivatedRoute, useValue: {} },
        { provide: HospitalScopeUrlService, useValue: {} },
        { provide: TranslateService, useValue: { instant: (key: string) => key } },
      ],
    });
    component = TestBed.runInInjectionContext(() => new TreatmentPlansComponent());
  });

  it('never offers APPROVED to the author on a new or unapproved plan', () => {
    component.form = component.emptyForm();
    expect(component.treatmentPlanStatuses.map((s) => s.value)).not.toContain('APPROVED');
    component.form = { ...component.emptyForm(), status: 'APPROVED' };
    expect(component.treatmentPlanStatuses.map((s) => s.value)).toContain('APPROVED');
  });

  it("offers the approval to another clinician, never to the plan's author", () => {
    expect(component.canApprovePlan(plan({}))).toBeTrue();
    expect(component.canApprovePlan(plan({ authorStaffId: 's-colleague' }))).toBeFalse();
    expect(component.canApprovePlan(plan({ authorUserId: 'u-colleague' }))).toBeFalse();
    expect(component.canApprovePlan(plan({ status: 'APPROVED' }))).toBeFalse();
  });

  it("approves through the colleague's review", () => {
    component.approvePlan(plan({}));
    expect(plans.addReview).toHaveBeenCalledWith('tp1', {
      reviewerStaffId: 's-colleague',
      action: 'APPROVED',
    });
    expect(toast.success).toHaveBeenCalledWith('TREATMENT.PLAN_APPROVED');
  });
});
