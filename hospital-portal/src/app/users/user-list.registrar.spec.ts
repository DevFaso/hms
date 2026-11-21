import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Route } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, of, throwError } from 'rxjs';

import { USER_ACCOUNT_ADMIN_ROLES, USER_REGISTRAR_ROLES, UserListComponent } from './user-list';
import { routes as appRoutes } from '../app.routes';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { HospitalService } from '../services/hospital.service';
import { ImpersonationService } from '../services/impersonation.service';
import { RoleService } from '../services/role.service';
import { UserService } from '../services/user.service';

/**
 * QA r65 D2762: an administrator (ROLE_ADMIN) reads the user directory and was
 * offered « Inscrire »: a form whose submit always answered 403, on a page
 * that also asked `GET /roles` and got a 403 on every visit.
 *
 * Platform admin rules 2026-11-20, rule 1a, decided it: the organisation
 * admin DOES register staff, at any hospital of its own organisation, and
 * administers no account. So « Inscrire » is shown exactly to the roles the
 * server lets register, and edit/delete exactly to those it lets administer.
 */
describe('UserListComponent — « Inscrire » only for a role the server lets register (QA r65 D2762)', () => {
  let roleList: jasmine.Spy;
  let hospitalList: jasmine.Spy;
  let myHospital: jasmine.Spy;
  let organisationHospitals: jasmine.Spy;
  let adminRegister: jasmine.Spy;
  let organisation: { id: string; name: string; lifecycleState?: string }[];

  beforeEach(() => {
    organisation = [
      { id: 'h-a', name: 'Clinique Test A' },
      { id: 'h-b', name: 'Clinique Test B' },
    ];
  });

  const manageableUser = {
    id: 'u2',
    username: 'test.medecin',
    firstName: 'Test',
    lastName: 'Médecin',
    active: true,
    manageable: true,
  };

  function render(
    activeRole: string,
    organisationAnswer: () => Observable<unknown> = () => of(organisation),
  ): ComponentFixture<UserListComponent> {
    roleList = jasmine.createSpy('roles').and.returnValue(of([]));
    hospitalList = jasmine.createSpy('hospitals').and.returnValue(of([]));
    myHospital = jasmine
      .createSpy('myHospital')
      .and.returnValue(of({ id: 'h-1', name: 'Clinique' }));
    organisationHospitals = jasmine
      .createSpy('organisationHospitals')
      .and.callFake(() => organisationAnswer());
    adminRegister = jasmine.createSpy('adminRegister').and.returnValue(of({ id: 'u-new' }));
    const page = { content: [manageableUser], number: 0, totalPages: 1, totalElements: 1 };
    TestBed.configureTestingModule({
      imports: [UserListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: { getUserProfile: () => ({ id: 'me' }), getUserId: () => 'me' },
        },
        {
          provide: UserService,
          useValue: { list: () => of(page), search: () => of(page), adminRegister },
        },
        { provide: RoleService, useValue: { list: roleList } },
        {
          provide: HospitalService,
          useValue: {
            list: hospitalList,
            getMyHospitalAsResponse: myHospital,
            organisationHospitals,
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'warning', 'info']),
        },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => activeRole === 'ROLE_SUPER_ADMIN',
            hasAnyActiveRole: (roles: string[]) => roles.includes(activeRole),
          },
        },
        { provide: ImpersonationService, useValue: { active: () => null } },
      ],
    });
    const fixture = TestBed.createComponent(UserListComponent);
    fixture.detectChanges();
    return fixture;
  }

  const registerButton = (fixture: ComponentFixture<unknown>) =>
    (fixture.nativeElement as HTMLElement).querySelector('[data-testid="users-register"]');

  afterEach(() => TestBed.resetTestingModule());

  it('an organisation admin registers staff, and is offered no edit, delete or two-step reset of an account', () => {
    const fixture = render('ROLE_ADMIN');

    expect(fixture.componentInstance.canRegister).toBeTrue();
    expect(registerButton(fixture)).not.toBeNull();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Médecin');
    // Even a row the server marked manageable: this role administers no account.
    expect(fixture.componentInstance.isManageable(manageableUser as never)).toBeFalse();
    expect((fixture.nativeElement as HTMLElement).querySelector('.action-link.delete')).toBeNull();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('.action-link.mfa-reset'),
    ).toBeNull();
    expect(roleList).toHaveBeenCalled();
  });

  it('an organisation admin picks the hospital among those of its own organisation, from the server', () => {
    const fixture = render('ROLE_ADMIN');
    const component = fixture.componentInstance;

    // Its own list: never every hospital of the platform, never "my hospital" alone.
    expect(organisationHospitals).toHaveBeenCalled();
    expect(hospitalList).not.toHaveBeenCalled();
    expect(myHospital).not.toHaveBeenCalled();
    expect(component.hospitalLocked).toBeFalse();

    component.openCreate();
    fixture.detectChanges();
    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLOptionElement>(
        '#hospitalId option',
      ),
    ).map((option) => option.value);
    expect(options).toEqual(['', 'h-a', 'h-b']);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="register-org-scope"]'),
    ).not.toBeNull();
  });

  it('an organisation admin is offered staff roles only: no admin role, no patient', () => {
    const fixture = render('ROLE_ADMIN');
    const component = fixture.componentInstance;
    component.availableRoles.set(
      [
        'ROLE_NURSE',
        'ROLE_DOCTOR',
        'ROLE_HOSPITAL_ADMIN',
        'ROLE_ADMIN',
        'ROLE_SUPER_ADMIN',
        'ROLE_PATIENT',
      ].map((code) => ({ id: code, code, name: code }) as never),
    );

    expect(component.grantableRoles().map((role) => role.code)).toEqual([
      'ROLE_NURSE',
      'ROLE_DOCTOR',
    ]);
  });

  it('an organisation admin with no hospital chosen is stopped on the hospital field, and nothing is sent', () => {
    const fixture = render('ROLE_ADMIN');
    const component = fixture.componentInstance;
    component.openCreate();
    component.createForm.firstName = 'Test';
    component.createForm.lastName = 'Infirmier';
    component.createForm.email = 'test.infirmier@example.com';
    component.createForm.phoneNumber = '+22670000001';
    component.selectedRoles = ['ROLE_NURSE'];
    fixture.detectChanges();

    component.submitCreate();
    fixture.detectChanges();

    expect(adminRegister).not.toHaveBeenCalled();
    const hospital = (fixture.nativeElement as HTMLElement).querySelector('#hospitalId');
    expect(hospital?.getAttribute('aria-invalid')).toBe('true');

    component.createForm.hospitalId = 'h-b';
    component.submitCreate();
    expect(adminRegister).toHaveBeenCalledTimes(1);
    expect(adminRegister.calls.mostRecent().args[0].hospitalId).toBe('h-b');
  });

  it('a failed load of the organisation hospitals is said, not swallowed', () => {
    const fixture = render('ROLE_ADMIN', () => throwError(() => new Error('down')));

    const toast = TestBed.inject(ToastService) as jasmine.SpyObj<ToastService>;
    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(fixture.componentInstance.availableHospitals()).toEqual([]);
  });

  it('with one hospital in the organisation it is chosen; a closed hospital is not offered', () => {
    organisation = [
      { id: 'h-a', name: 'Clinique Test A' },
      { id: 'h-z', name: 'Clinique fermée', lifecycleState: 'ARCHIVED' },
    ];
    const fixture = render('ROLE_ADMIN');
    const component = fixture.componentInstance;

    expect(component.availableHospitals()).toEqual([{ id: 'h-a', name: 'Clinique Test A' }]);
    component.openCreate();
    expect(component.createForm.hospitalId).toBe('h-a');
  });

  it('a hospital admin and a super-admin keep « Inscrire » and their pickers', () => {
    const hospitalAdmin = render('ROLE_HOSPITAL_ADMIN');
    expect(registerButton(hospitalAdmin)).not.toBeNull();
    expect(hospitalAdmin.componentInstance.isManageable(manageableUser as never)).toBeTrue();
    expect(roleList).toHaveBeenCalled();
    expect(myHospital).toHaveBeenCalled();
    // A hospital admin stays locked to its own hospital: the organisation list is not its to ask.
    expect(hospitalAdmin.componentInstance.hospitalLocked).toBeTrue();
    expect(organisationHospitals).not.toHaveBeenCalled();
    TestBed.resetTestingModule();

    const superAdmin = render('ROLE_SUPER_ADMIN');
    expect(registerButton(superAdmin)).not.toBeNull();
    expect(roleList).toHaveBeenCalled();
    expect(hospitalList).toHaveBeenCalled();
  });

  it('class guard: every role the /users route admits either registers, or is offered no register form', () => {
    function find(table: Route[]): Route | undefined {
      for (const route of table) {
        if (route.path === 'users') return route;
        const nested = route.children ? find(route.children) : undefined;
        if (nested) return nested;
      }
      return undefined;
    }
    const admitted = find(appRoutes)?.data?.['roles'] as string[];
    expect(admitted).toContain('ROLE_ADMIN');
    for (const role of admitted) {
      const fixture = render(role);
      expect(registerButton(fixture) !== null)
        .withContext(`${role}: « Inscrire » is shown exactly to the registrar roles`)
        .toBe(USER_REGISTRAR_ROLES.includes(role));
      expect(fixture.componentInstance.isManageable(manageableUser as never))
        .withContext(`${role}: an account is edited or deleted exactly by the account-admin roles`)
        .toBe(USER_ACCOUNT_ADMIN_ROLES.includes(role));
      TestBed.resetTestingModule();
    }
    // Every account-admin role registers; the reverse is not true (the organisation admin).
    expect(
      USER_ACCOUNT_ADMIN_ROLES.every((role) => USER_REGISTRAR_ROLES.includes(role)),
    ).toBeTrue();
    expect(USER_ACCOUNT_ADMIN_ROLES).not.toContain('ROLE_ADMIN');
  });
});
