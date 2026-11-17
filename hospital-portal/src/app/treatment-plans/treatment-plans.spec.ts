import { ElementRef } from '@angular/core';
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
import { StaffResponse, StaffService } from '../services/staff.service';
import { PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { AuthService } from '../auth/auth.service';
import { roleContextStub } from '../testing/role-context.stub';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../testing/form-refusal-behaviour';
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
        // QA D1306 (round 28): the component marks refused fields in its host.
        { provide: ElementRef, useValue: new ElementRef(document.createElement('div')) },
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
        // QA D1332: the author is the signed-in clinician's staff row.
        { provide: AuthService, useValue: { getUserProfile: () => ({ id: 'u1' }) } },
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
    component['allStaff'].set([
      { id: 's1', userId: 'u1', hospitalId: 'h1', name: 'Dr Moi' } as StaffResponse,
      { id: 's2', userId: 'u2', hospitalId: 'h1', name: 'Dr Collègue' } as StaffResponse,
    ]);
  });

  it('does not post an empty plan; the toast names the missing fields', () => {
    component.form = component.emptyForm();
    component.form.hospitalId = 'h1';

    component.submitForm();

    expect(plans.create).not.toHaveBeenCalled();
    // QA D1332: the author is the caller, filled for them, so it is never missing.
    expect(toast.error).toHaveBeenCalledWith(
      'COMMON.MISSING_REQUIRED_FIELDS: PATIENTS.PATIENT, TREATMENT.PROBLEM_STATEMENT',
    );
  });

  it('QA D1332: a create is sent with the caller as author, whatever the form held', () => {
    plans.create.and.returnValue(of({ id: 'tp1' } as TreatmentPlanResponse));
    component.form = {
      ...component.emptyForm(),
      patientId: 'p1',
      hospitalId: 'h1',
      authorStaffId: 's2',
      problemStatement: 'Diabète déséquilibré',
    };

    component.submitForm();

    expect(plans.create).toHaveBeenCalledWith(jasmine.objectContaining({ authorStaffId: 's1' }));
  });

  it('QA D1332: a caller with no staff row at the hospital is refused before sending', () => {
    component.form = {
      ...component.emptyForm(),
      patientId: 'p1',
      hospitalId: 'h-other',
      problemStatement: 'Diabète déséquilibré',
    };

    component.submitForm();

    expect(plans.create).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith(
      'COMMON.MISSING_REQUIRED_FIELDS: TREATMENT.AUTHOR_STAFF',
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
        // QA D1306 (round 28): the component marks refused fields in its host.
        { provide: ElementRef, useValue: new ElementRef(document.createElement('div')) },
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

describe('TreatmentPlansComponent author and refusals (rendered, QA D1332 / D1256 class)', () => {
  const staff = [
    { id: 's1', userId: 'u1', hospitalId: 'h1', name: 'Dr Moi', roleCode: 'ROLE_DOCTOR' },
    { id: 's2', userId: 'u2', hospitalId: 'h1', name: 'Dr Collègue', roleCode: 'ROLE_DOCTOR' },
  ] as StaffResponse[];
  let attached: HTMLElement | null = null;

  function render(superAdmin: boolean, create: jasmine.Spy) {
    TestBed.configureTestingModule({
      imports: [TreatmentPlansComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        {
          provide: TreatmentPlanService,
          useValue: { getAll: () => of([]), create, update: create },
        },
        {
          provide: HospitalService,
          useValue: {
            list: () => of([{ id: 'h1', name: 'CHU' }]),
            getMyHospitalAsResponse: () => of({ id: 'h1', name: 'CHU' }),
          },
        },
        { provide: StaffService, useValue: { list: () => of(staff) } },
        { provide: PatientService, useValue: { list: () => of([]) } },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']),
        },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin,
            hospitalId: 'h1',
            roles: [superAdmin ? 'ROLE_SUPER_ADMIN' : 'ROLE_NURSE'],
          }),
        },
        { provide: AuthService, useValue: { getUserProfile: () => ({ id: 'u1' }) } },
        { provide: HospitalScopeUrlService, useValue: { applyUrlScopeSync: () => undefined } },
      ],
    });
    const fixture = TestBed.createComponent(TreatmentPlansComponent);
    attached = fixture.nativeElement as HTMLElement;
    document.body.appendChild(attached);
    fixture.detectChanges();
    fixture.componentInstance.openCreate();
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    attached?.remove();
    attached = null;
  });

  it('a clinician is not offered colleagues: their own name is shown, read-only', () => {
    const fixture = render(false, jasmine.createSpy('create'));
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('select#tp-authorStaff')).toBeNull();
    const self = root.querySelector<HTMLInputElement>('[data-testid="tp-author-self"]');
    expect(self?.readOnly).toBeTrue();
    expect(self?.value).toBe('Dr Moi');
  });

  it('a super-admin, whom the server lets name another author, keeps the staff list', () => {
    const fixture = render(true, jasmine.createSpy('create'));
    const select = (fixture.nativeElement as HTMLElement).querySelector('select#tp-authorStaff');
    expect(select?.textContent).toContain('Dr Collègue');
  });

  it('an empty press marks every blank required field, starred, and sends nothing', () => {
    const create = jasmine.createSpy('create');
    const fixture = render(false, create);
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="tp-submit"]',
      sent: create,
    });
    expect(marked).toContain('tp-problem');
    expect(marked).not.toContain('tp-authorStaff');
  });

  it('a 400 naming fields marks each with the server reason', () => {
    const create = jasmine
      .createSpy('create')
      .and.returnValue(throwError(() => fieldErrorsResponse({ problemStatement: 'trop long' })));
    const fixture = render(false, create);
    const component = fixture.componentInstance;
    component.form = { ...component.form, patientId: 'p1', problemStatement: 'Diabète' };
    component.submitForm();
    fixture.detectChanges();
    expect(create).toHaveBeenCalled();
    expectFieldsMarked(fixture, { 'tp-problem': 'trop long' });
  });
});
