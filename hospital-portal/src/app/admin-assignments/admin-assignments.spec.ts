import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { AdminAssignmentsComponent } from './admin-assignments';
import { AssignmentAdminService, AssignmentResponse } from '../services/assignment-admin.service';
import { HospitalService } from '../services/hospital.service';
import { RoleResponse, RoleService } from '../services/role.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

/**
 * QA D194: a PATIENT assignment is activated by the patient's verification,
 * so the server refuses active=true; the create form had Actif checked by
 * default and sent it, which the server refused.
 */
describe('AdminAssignmentsComponent — patient assignments start inactive (QA D194)', () => {
  let component: AdminAssignmentsComponent;
  let service: jasmine.SpyObj<AssignmentAdminService>;

  beforeEach(() => {
    service = jasmine.createSpyObj<AssignmentAdminService>('AssignmentAdminService', [
      'create',
      'update',
    ]);
    service.create.and.returnValue(of({} as AssignmentResponse));
    TestBed.configureTestingModule({
      providers: [
        { provide: AssignmentAdminService, useValue: service },
        { provide: HospitalService, useValue: {} },
        { provide: RoleService, useValue: {} },
        { provide: RoleContextService, useValue: {} },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: TranslateService, useValue: { instant: (k: string) => k } },
        { provide: ActivatedRoute, useValue: {} },
      ],
    });
    component = TestBed.runInInjectionContext(() => new AdminAssignmentsComponent());
    spyOn(component, 'load');
    component.roles.set([
      { id: 'r-patient', code: 'ROLE_PATIENT' } as RoleResponse,
      { id: 'r-nurse', code: 'ROLE_NURSE' } as RoleResponse,
    ]);
    component.formUserIdentifier.set('qa.user');
  });

  it('sends a patient assignment inactive even though Actif defaults to checked', () => {
    component.formActive.set(true);
    component.formRoleId.set('r-patient');
    expect(component.patientRoleSelected()).toBeTrue();
    component.submitCreate();
    expect(service.create.calls.mostRecent().args[0].active).toBeFalse();
  });

  it('keeps the chosen state for any other role', () => {
    component.formActive.set(true);
    component.formRoleId.set('r-nurse');
    expect(component.patientRoleSelected()).toBeFalse();
    component.submitCreate();
    expect(service.create.calls.mostRecent().args[0].active).toBeTrue();
  });
});

/**
 * QA D664 replay (rendered): HOSPITAL_ADMIN_A opens /admin-assignments and
 * "Nouvelle affectation". The role dropdown offered Patient(e), Super
 * administrateur, Administrateur and Administrateur hôpital, each refused by
 * the server on submit. It now lists only what the caller may grant.
 */
describe('AdminAssignmentsComponent — the role dropdown lists only grantable roles (QA D664)', () => {
  const roleRows = [
    { id: 'r-patient', code: 'ROLE_PATIENT', name: 'ROLE_PATIENT', authority: 'ROLE_PATIENT' },
    {
      id: 'r-super',
      code: 'ROLE_SUPER_ADMIN',
      name: 'ROLE_SUPER_ADMIN',
      authority: 'ROLE_SUPER_ADMIN',
    },
    { id: 'r-admin', code: 'ROLE_ADMIN', name: 'ROLE_ADMIN', authority: 'ROLE_ADMIN' },
    {
      id: 'r-hadmin',
      code: 'ROLE_HOSPITAL_ADMIN',
      name: 'ROLE_HOSPITAL_ADMIN',
      authority: 'ROLE_HOSPITAL_ADMIN',
    },
    { id: 'r-nurse', code: 'ROLE_NURSE', name: 'ROLE_NURSE', authority: 'ROLE_NURSE' },
  ] as RoleResponse[];

  function render(superAdmin: boolean) {
    const service = jasmine.createSpyObj<AssignmentAdminService>('AssignmentAdminService', [
      'list',
    ]);
    service.list.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, number: 0 }) as never,
    );
    TestBed.configureTestingModule({
      imports: [AdminAssignmentsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AssignmentAdminService, useValue: service },
        { provide: HospitalService, useValue: { list: () => of([]) } },
        { provide: RoleService, useValue: { list: () => of(roleRows) } },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => superAdmin,
            hasAnyActiveRole: () => superAdmin,
            activeHospitalId: 'hosp-a',
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    });
    const fixture = TestBed.createComponent(AdminAssignmentsComponent);
    fixture.detectChanges();
    fixture.componentInstance.openCreate();
    fixture.detectChanges();
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      '[role="dialog"] select',
    ) as HTMLSelectElement;
    return Array.from(select.options)
      .map((o) => o.value)
      .filter((v) => v !== '');
  }

  it('offers a hospital admin neither the patient role nor any admin role', () => {
    expect(render(false)).toEqual(['r-nurse']);
  });

  it('offers a super-admin the admin roles, but never the patient role', () => {
    expect(render(true)).toEqual(['r-super', 'r-admin', 'r-hadmin', 'r-nurse']);
  });
});
