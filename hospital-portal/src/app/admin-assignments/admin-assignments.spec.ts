import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { AdminAssignmentsComponent } from './admin-assignments';
import { AssignmentAdminService, AssignmentResponse } from '../services/assignment-admin.service';
import { HospitalService } from '../services/hospital.service';
import { RoleResponse, RoleService } from '../services/role.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
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
        { provide: AuthService, useValue: { getUserProfile: () => null, getUserId: () => null } },
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
        { provide: AuthService, useValue: { getUserProfile: () => null, getUserId: () => null } },
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

/**
 * QA D818: "Nouvelle affectation" offered the archived hospital, which the
 * server then refused with 409. Every picker that grants a role offers only
 * open hospitals; the list filter still finds old rows at an archived one.
 */
describe('AdminAssignmentsComponent — no archived hospital in a granting picker (QA D818)', () => {
  const hospitals = [
    { id: 'h-open', name: 'CMA Pissy', lifecycleState: 'ACTIVE' },
    { id: 'h-suspended', name: 'CSPS Tanghin', lifecycleState: 'SUSPENDED' },
    { id: 'h-archived', name: 'Ancien CMA', lifecycleState: 'ARCHIVED' },
    { id: 'h-purge', name: 'CMA à purger', lifecycleState: 'PENDING_PURGE' },
    { id: 'h-legacy', name: 'Sans état' },
  ];

  function render() {
    const service = jasmine.createSpyObj<AssignmentAdminService>('AssignmentAdminService', [
      'list',
    ]);
    service.list.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, number: 0 }) as never,
    );
    TestBed.configureTestingModule({
      imports: [AdminAssignmentsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AuthService, useValue: { getUserProfile: () => null, getUserId: () => null } },
        { provide: AssignmentAdminService, useValue: service },
        { provide: HospitalService, useValue: { list: () => of(hospitals) } },
        { provide: RoleService, useValue: { list: () => of([]) } },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => true,
            hasAnyActiveRole: () => true,
            activeHospitalId: null,
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
    return fixture;
  }

  const OPEN = ['h-open', 'h-suspended', 'h-legacy'];
  const optionValues = (select: Element | null) =>
    Array.from((select as HTMLSelectElement).options)
      .map((o) => o.value)
      .filter((v) => v !== '');

  it('the new-assignment hospital picker offers only open hospitals', () => {
    const fixture = render();
    fixture.componentInstance.openCreate();
    fixture.detectChanges();
    const selects = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[role="dialog"] select',
    );
    // [0] is the role, [1] the hospital.
    expect(optionValues(selects[1])).toEqual(OPEN);
  });

  it('the list filter still lists the archived hospital, for its old rows', () => {
    const fixture = render();
    const filter = (fixture.nativeElement as HTMLElement).querySelector('select.filter-select');
    expect(optionValues(filter)).toContain('h-archived');
  });

  it('a list filtered on an archived hospital does not preselect it in a new assignment', () => {
    const fixture = render();
    fixture.componentInstance.filterHospitalId.set('h-archived');
    fixture.componentInstance.openCreate();
    expect(fixture.componentInstance.formHospitalId()).toBe('');
    fixture.componentInstance.openImport();
    expect(fixture.componentInstance.importDefaultHospitalId()).toBe('');
    fixture.componentInstance.filterHospitalId.set('h-open');
    fixture.componentInstance.openCreate();
    expect(fixture.componentInstance.formHospitalId()).toBe('h-open');
    fixture.componentInstance.openImport();
    expect(fixture.componentInstance.importDefaultHospitalId()).toBe('h-open');
  });

  it('the multi-assign checkboxes offer only open hospitals', () => {
    const fixture = render();
    fixture.componentInstance.openMulti();
    fixture.detectChanges();
    const labels = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[role="dialog"] .check-field'),
    ).map((l) => l.textContent!.trim());
    expect(labels).toContain('CMA Pissy');
    expect(labels).not.toContain('Ancien CMA');
    expect(labels).not.toContain('CMA à purger');
  });

  it('the import default hospital offers only open hospitals', () => {
    const fixture = render();
    fixture.componentInstance.openImport();
    fixture.detectChanges();
    const hospitalSelects = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[role="dialog"] select'),
    ).filter((sel) => optionValues(sel).includes('h-open'));
    expect(hospitalSelects.length).toBe(1);
    expect(optionValues(hospitalSelects[0])).toEqual(OPEN);
  });

  it('editing a row at a hospital archived since still shows that hospital', () => {
    const fixture = render();
    fixture.componentInstance.openEdit({
      id: 'a-1',
      roleId: 'r-nurse',
      hospitalId: 'h-archived',
      active: false,
    } as AssignmentResponse);
    expect(fixture.componentInstance.formHospitalOptions().map((h) => h.id)).toEqual([
      ...OPEN,
      'h-archived',
    ]);
  });
});

/**
 * QA round 14: a 409 on delete said "a staff record still references this
 * assignment" whatever was in the way (a department, encounters...). The
 * server names it in the reader's language; the toast shows that.
 */
describe('AdminAssignmentsComponent — delete refusal names what still uses the assignment', () => {
  let component: AdminAssignmentsComponent;
  let service: jasmine.SpyObj<AssignmentAdminService>;
  let toast: jasmine.SpyObj<ToastService>;
  const row = { id: 'a-1' } as AssignmentResponse;

  beforeEach(() => {
    service = jasmine.createSpyObj<AssignmentAdminService>('AssignmentAdminService', ['delete']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { getUserProfile: () => null, getUserId: () => null } },
        { provide: AssignmentAdminService, useValue: service },
        { provide: HospitalService, useValue: {} },
        { provide: RoleService, useValue: {} },
        { provide: RoleContextService, useValue: {} },
        { provide: ToastService, useValue: toast },
        { provide: TranslateService, useValue: { instant: (k: string) => k } },
        { provide: ActivatedRoute, useValue: {} },
      ],
    });
    component = TestBed.runInInjectionContext(() => new AdminAssignmentsComponent());
    spyOn(window, 'confirm').and.returnValue(true);
  });

  it("shows the server's sentence on a 409", () => {
    service.delete.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { message: 'Impossible de supprimer l’affectation : … des services.' },
          }),
      ),
    );
    component.delete(row);
    expect(toast.error).toHaveBeenCalledWith(
      'Impossible de supprimer l’affectation : … des services.',
    );
  });

  it('falls back to the generic conflict text when the 409 has no message', () => {
    service.delete.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: null })),
    );
    component.delete(row);
    expect(toast.error).toHaveBeenCalledWith('ASSIGN_ADMIN.DELETE_CONFLICT');
  });

  it('keeps the generic error for anything other than a 409', () => {
    service.delete.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ status: 500, error: { message: 'Internal Server Error' } }),
      ),
    );
    component.delete(row);
    expect(toast.error).toHaveBeenCalledWith('ASSIGN_ADMIN.ACTION_ERROR');
  });
});

/**
 * QA D902 replay: the dev super-admin opened /admin-assignments with no
 * filter and clicked the trash icon on the first row, their own global
 * assignment; it was deleted and the account lost super-admin. The own row
 * now offers no delete or deactivate, and the server's 409 reads in words.
 */
describe('AdminAssignmentsComponent — the signed-in account cannot remove its own row (QA D902)', () => {
  let component: AdminAssignmentsComponent;
  let service: jasmine.SpyObj<AssignmentAdminService>;
  let toast: jasmine.SpyObj<ToastService>;
  const own = { id: 'a-own', userId: 'u-me', active: true } as AssignmentResponse;
  const other = { id: 'a-other', userId: 'u-other', active: true } as AssignmentResponse;

  beforeEach(() => {
    service = jasmine.createSpyObj<AssignmentAdminService>('AssignmentAdminService', [
      'deactivate',
    ]);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: { getUserProfile: () => ({ id: 'u-me' }), getUserId: () => 'u-me' },
        },
        { provide: AssignmentAdminService, useValue: service },
        { provide: HospitalService, useValue: {} },
        { provide: RoleService, useValue: {} },
        { provide: RoleContextService, useValue: {} },
        { provide: ToastService, useValue: toast },
        { provide: TranslateService, useValue: { instant: (k: string) => k } },
        { provide: ActivatedRoute, useValue: {} },
      ],
    });
    component = TestBed.runInInjectionContext(() => new AdminAssignmentsComponent());
  });

  it('treats only the caller’s own row as their own', () => {
    expect(component.isOwnRow(own)).toBeTrue();
    expect(component.isOwnRow(other)).toBeFalse();
    expect(component.isOwnRow({ id: 'a-x' } as AssignmentResponse)).toBeFalse();
  });

  it('shows the server’s sentence when a deactivation is refused with a 409', () => {
    const sentence =
      'Cette modification laisserait la plateforme sans aucun super administrateur actif. Attribuez d’abord ce rôle à un autre compte.';
    service.deactivate.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { message: sentence } })),
    );
    component.deactivate(other);
    expect(toast.error).toHaveBeenCalledWith(sentence);
  });
});

describe('AdminAssignmentsComponent — no trash icon on the caller’s own row, rendered (QA D902)', () => {
  it('renders delete on another account’s row and not on the signed-in account’s', () => {
    const rows = [
      { id: 'a-own', userId: 'u-me', active: true, roleCode: 'ROLE_SUPER_ADMIN' },
      { id: 'a-other', userId: 'u-other', active: true, roleCode: 'ROLE_NURSE' },
    ] as AssignmentResponse[];
    const service = jasmine.createSpyObj<AssignmentAdminService>('AssignmentAdminService', [
      'list',
    ]);
    service.list.and.returnValue(
      of({ content: rows, totalElements: 2, totalPages: 1, number: 0 }) as never,
    );
    TestBed.configureTestingModule({
      imports: [AdminAssignmentsComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: AuthService,
          useValue: { getUserProfile: () => ({ id: 'u-me' }), getUserId: () => 'u-me' },
        },
        { provide: AssignmentAdminService, useValue: service },
        { provide: HospitalService, useValue: { list: () => of([]) } },
        { provide: RoleService, useValue: { list: () => of([]) } },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => true,
            hasAnyActiveRole: () => true,
            activeHospitalId: null,
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
    const bodyRows = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr'),
    ) as HTMLElement[];
    expect(bodyRows.length).toBe(2);
    expect(bodyRows[0].querySelector('[data-test="assignment-delete"]')).toBeNull();
    expect(bodyRows[1].querySelector('[data-test="assignment-delete"]')).not.toBeNull();
  });
});

/**
 * QA D915: a hospital admin ticked "Actif" on an inactive row and the server
 * refused it. Only a super-admin switches a row on directly; the form now says
 * so and keeps the row as it is.
 */
describe('AdminAssignmentsComponent — a hospital admin is not offered "Active" on an inactive row (QA D915)', () => {
  let component: AdminAssignmentsComponent;
  let service: jasmine.SpyObj<AssignmentAdminService>;
  let superAdmin: boolean;

  beforeEach(() => {
    superAdmin = false;
    service = jasmine.createSpyObj<AssignmentAdminService>('AssignmentAdminService', ['update']);
    service.update.and.returnValue(of({} as AssignmentResponse));
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { getUserProfile: () => null, getUserId: () => null } },
        { provide: AssignmentAdminService, useValue: service },
        { provide: HospitalService, useValue: {} },
        { provide: RoleService, useValue: {} },
        { provide: RoleContextService, useValue: { isSuperAdmin: () => superAdmin } },
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
    component.roles.set([{ id: 'r-nurse', code: 'ROLE_NURSE' } as RoleResponse]);
  });

  const inactiveRow = {
    id: 'a1',
    userId: 'u1',
    roleId: 'r-nurse',
    hospitalId: 'h1',
    active: false,
  } as AssignmentResponse;

  it('locks "Active" for a hospital admin and leaves the state out of the save', () => {
    component.openEdit(inactiveRow);
    expect(component.activationLocked()).toBeTrue();
    component.formActive.set(true);
    component.submitEdit();
    expect(service.update.calls.mostRecent().args[1].active).toBeUndefined();
  });

  it('still offers it on an active row, and to a super-admin', () => {
    component.openEdit({ ...inactiveRow, active: true });
    expect(component.activationLocked()).toBeFalse();
    superAdmin = true;
    component.openEdit(inactiveRow);
    expect(component.activationLocked()).toBeFalse();
    component.formActive.set(true);
    component.submitEdit();
    expect(service.update.calls.mostRecent().args[1].active).toBeTrue();
  });
});
