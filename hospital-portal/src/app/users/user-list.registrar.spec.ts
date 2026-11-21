import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Route } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { USER_REGISTRAR_ROLES, UserListComponent } from './user-list';
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
 * offered « Inscrire »: a form whose submit always answered 403 (the role is
 * not in the server's USER_REGISTRAR_AUTHORITIES), on a page that also asked
 * `GET /roles` and got a 403 on every visit.
 */
describe('UserListComponent — « Inscrire » only for a role the server lets register (QA r65 D2762)', () => {
  let roleList: jasmine.Spy;
  let hospitalList: jasmine.Spy;
  let myHospital: jasmine.Spy;

  const manageableUser = {
    id: 'u2',
    username: 'test.medecin',
    firstName: 'Test',
    lastName: 'Médecin',
    active: true,
    manageable: true,
  };

  function render(activeRole: string): ComponentFixture<UserListComponent> {
    roleList = jasmine.createSpy('roles').and.returnValue(of([]));
    hospitalList = jasmine.createSpy('hospitals').and.returnValue(of([]));
    myHospital = jasmine
      .createSpy('myHospital')
      .and.returnValue(of({ id: 'h-1', name: 'Clinique' }));
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
          useValue: { list: () => of(page), search: () => of(page) },
        },
        { provide: RoleService, useValue: { list: roleList } },
        {
          provide: HospitalService,
          useValue: { list: hospitalList, getMyHospitalAsResponse: myHospital },
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

  it('an administrator is offered no « Inscrire », no row action, and asks for no role or hospital list', () => {
    const fixture = render('ROLE_ADMIN');

    expect(fixture.componentInstance.canRegister).toBeFalse();
    expect(registerButton(fixture)).toBeNull();
    // The directory itself is theirs to read.
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Médecin');
    // No edit, delete or two-factor reset: the server has no write for this role here.
    expect(fixture.componentInstance.isManageable(manageableUser as never)).toBeFalse();
    expect((fixture.nativeElement as HTMLElement).querySelector('.action-link.delete')).toBeNull();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('.action-link.mfa-reset'),
    ).toBeNull();
    // GET /roles answered 403 on every visit (the report's second finding).
    expect(roleList).not.toHaveBeenCalled();
    expect(hospitalList).not.toHaveBeenCalled();
    expect(myHospital).not.toHaveBeenCalled();
  });

  it('a hospital admin and a super-admin keep « Inscrire » and their pickers', () => {
    const hospitalAdmin = render('ROLE_HOSPITAL_ADMIN');
    expect(registerButton(hospitalAdmin)).not.toBeNull();
    expect(hospitalAdmin.componentInstance.isManageable(manageableUser as never)).toBeTrue();
    expect(roleList).toHaveBeenCalled();
    expect(myHospital).toHaveBeenCalled();
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
      TestBed.resetTestingModule();
    }
  });
});
