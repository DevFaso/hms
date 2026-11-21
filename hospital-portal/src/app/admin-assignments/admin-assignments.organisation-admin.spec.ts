import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Route, convertToParamMap } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ASSIGNMENT_FULL_ADMIN_ROLES, AdminAssignmentsComponent } from './admin-assignments';
import { routes as appRoutes } from '../app.routes';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { AssignmentAdminService, AssignmentResponse } from '../services/assignment-admin.service';
import { HospitalService } from '../services/hospital.service';
import { RoleService } from '../services/role.service';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';

/**
 * Platform admin rules 2026-11-20, rule 1a: an organisation admin (ROLE_ADMIN)
 * works the staff assignments of every hospital of its own organisation, with
 * the reduced set of actions the server admits it on. Rule 1c: nobody is
 * offered a fresh activation code for their own account.
 */
describe('AdminAssignmentsComponent — the organisation admin (platform admin rules, rule 1a)', () => {
  const ME = 'user-me';
  let service: jasmine.SpyObj<AssignmentAdminService>;
  let hospitalList: jasmine.Spy;
  let organisationHospitals: jasmine.Spy;
  let toast: jasmine.SpyObj<ToastService>;

  const row = (over: Partial<AssignmentResponse> = {}): AssignmentResponse => ({
    id: 'a-1',
    assignmentCode: 'ASG-1',
    userId: 'user-nurse',
    userName: 'Test Infirmier 1',
    userEmail: 'infirmier1@example.test',
    hospitalId: 'h-b',
    hospitalName: 'Clinique Test B',
    roleCode: 'ROLE_NURSE',
    active: true,
    confirmationVerified: true,
    ...over,
  });

  function render(
    activeRole: string,
    rows: AssignmentResponse[] = [row()],
  ): ComponentFixture<AdminAssignmentsComponent> {
    service = jasmine.createSpyObj<AssignmentAdminService>('AssignmentAdminService', [
      'list',
      'create',
    ]);
    service.list.and.returnValue(
      of({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        size: 20,
        number: 0,
      }) as never,
    );
    service.create.and.returnValue(of(row({ id: 'a-new', active: false })) as never);
    hospitalList = jasmine.createSpy('list').and.returnValue(of([{ id: 'h-a', name: 'A' }]));
    organisationHospitals = jasmine.createSpy('organisationHospitals').and.returnValue(
      of([
        { id: 'h-a', name: 'Clinique Test A' },
        { id: 'h-b', name: 'Clinique Test B' },
      ]),
    );
    toast = jasmine.createSpyObj('ToastService', ['success', 'error', 'warning', 'info']);
    TestBed.configureTestingModule({
      imports: [AdminAssignmentsComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: AuthService,
          useValue: { getUserProfile: () => ({ id: ME }), getUserId: () => ME },
        },
        { provide: AssignmentAdminService, useValue: service },
        { provide: HospitalService, useValue: { list: hospitalList, organisationHospitals } },
        {
          provide: RoleService,
          useValue: {
            list: () =>
              of([
                { id: 'r-nurse', code: 'ROLE_NURSE', name: 'ROLE_NURSE' },
                { id: 'r-hadmin', code: 'ROLE_HOSPITAL_ADMIN', name: 'ROLE_HOSPITAL_ADMIN' },
                { id: 'r-admin', code: 'ROLE_ADMIN', name: 'ROLE_ADMIN' },
                { id: 'r-patient', code: 'ROLE_PATIENT', name: 'ROLE_PATIENT' },
              ]),
          },
        },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => activeRole === 'ROLE_SUPER_ADMIN',
            hasAnyActiveRole: (roles: string[]) => roles.includes(activeRole),
            activeHospitalId: 'h-a',
          },
        },
        { provide: ToastService, useValue: toast },
        { provide: ConfirmService, useValue: { ask: () => Promise.resolve(true) } },
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

  const all = (fixture: ComponentFixture<unknown>, selector: string) =>
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(selector));

  afterEach(() => TestBed.resetTestingModule());

  it('lists its whole organisation: no hospital is sent as a filter, and the hospitals come from its own list', () => {
    const fixture = render('ROLE_ADMIN');

    expect(service.list.calls.mostRecent().args[2]?.hospitalId).toBeUndefined();
    expect(organisationHospitals).toHaveBeenCalled();
    expect(hospitalList).not.toHaveBeenCalled();
    expect(all(fixture, '[data-testid="assignments-hospital-filter"]').length).toBe(0);
    expect(all(fixture, '[data-testid="assignments-org-scope"]').length).toBe(1);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Test Infirmier 1');
  });

  it('is offered one new assignment, the details, a new code and the switch-off; no import, multi-grant, edit or delete', () => {
    const fixture = render('ROLE_ADMIN');

    expect(all(fixture, '.header-actions button').length).toBe(1);
    expect(all(fixture, '[data-test="assignment-edit"]').length).toBe(0);
    expect(all(fixture, '[data-test="assignment-delete"]').length).toBe(0);
    expect(all(fixture, '[data-test="assignment-regenerate"]').length).toBe(1);
    expect(all(fixture, '.actions-cell .icon-btn.warn').length).toBe(1);
  });

  it('offers staff roles only, and names the hospital of every grant', () => {
    const fixture = render('ROLE_ADMIN');
    const component = fixture.componentInstance;

    expect(component.grantableRoles().map((role) => role.code)).toEqual(['ROLE_NURSE']);

    component.openCreate();
    component.formUserIdentifier.set('infirmier2@example.test');
    component.formRoleId.set('r-nurse');
    fixture.detectChanges();
    const hospital = all(fixture, '[data-test="assignment-hospital"]')[0] as HTMLSelectElement;
    expect(hospital.required).toBeTrue();

    component.submitCreate();
    expect(service.create).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();

    component.formHospitalId.set('h-b');
    component.submitCreate();
    expect(service.create).toHaveBeenCalledTimes(1);
    expect(service.create.calls.mostRecent().args[0].hospitalId).toBe('h-b');
  });

  it('a hospital admin and a super-admin keep every action, and never ask for an organisation list', () => {
    const hospitalAdmin = render('ROLE_HOSPITAL_ADMIN');
    expect(all(hospitalAdmin, '.header-actions button').length).toBe(3);
    expect(all(hospitalAdmin, '[data-test="assignment-edit"]').length).toBe(1);
    expect(all(hospitalAdmin, '[data-test="assignment-delete"]').length).toBe(1);
    expect(all(hospitalAdmin, '[data-testid="assignments-hospital-filter"]').length).toBe(1);
    expect(all(hospitalAdmin, '[data-testid="assignments-org-scope"]').length).toBe(0);
    // Its own hospital is still the default filter.
    expect(service.list.calls.mostRecent().args[2]?.hospitalId).toBe('h-a');
    expect(hospitalList).toHaveBeenCalled();
    expect(organisationHospitals).not.toHaveBeenCalled();
    const hospital = (() => {
      hospitalAdmin.componentInstance.openCreate();
      hospitalAdmin.detectChanges();
      return all(hospitalAdmin, '[data-test="assignment-hospital"]')[0] as HTMLSelectElement;
    })();
    expect(hospital.required).toBeFalse();
    TestBed.resetTestingModule();

    const superAdmin = render('ROLE_SUPER_ADMIN');
    expect(all(superAdmin, '.header-actions button').length).toBe(3);
    expect(all(superAdmin, '[data-test="assignment-delete"]').length).toBe(1);
    expect(organisationHospitals).not.toHaveBeenCalled();
  });

  it('rule 1c: nobody is offered a fresh activation code, a switch-off or a delete on their own row', () => {
    for (const role of ['ROLE_SUPER_ADMIN', 'ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN']) {
      const fixture = render(role, [
        row({ userId: ME, active: false, confirmationVerified: true }),
      ]);
      expect(all(fixture, '[data-test="assignment-regenerate"]').length)
        .withContext(`${role}: a new code is how a switched-off row comes back on`)
        .toBe(0);
      expect(all(fixture, '[data-test="assignment-delete"]').length).toBe(0);
      expect(all(fixture, '.actions-cell .icon-btn.warn').length).toBe(0);
      TestBed.resetTestingModule();
    }
  });

  it('class guard: every role the route admits has either every action or the reduced set', () => {
    function find(table: Route[]): Route | undefined {
      for (const route of table) {
        if (route.path === 'admin-assignments') return route;
        const nested = route.children ? find(route.children) : undefined;
        if (nested) return nested;
      }
      return undefined;
    }
    const admitted = find(appRoutes)?.data?.['roles'] as string[];
    expect(admitted).toContain('ROLE_ADMIN');
    expect(ASSIGNMENT_FULL_ADMIN_ROLES.every((role) => admitted.includes(role))).toBeTrue();
    for (const role of admitted) {
      const fixture = render(role);
      const full = ASSIGNMENT_FULL_ADMIN_ROLES.includes(role);
      expect(fixture.componentInstance.fullAdmin).withContext(role).toBe(full);
      expect(all(fixture, '[data-test="assignment-delete"]').length)
        .withContext(`${role}: delete is offered exactly to the full-admin roles`)
        .toBe(full ? 1 : 0);
      expect(all(fixture, '[data-test="assignment-edit"]').length)
        .withContext(`${role}: edit is offered exactly to the full-admin roles`)
        .toBe(full ? 1 : 0);
      TestBed.resetTestingModule();
    }
  });
});
