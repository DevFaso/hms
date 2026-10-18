import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { UserListComponent } from './user-list';
import { UserService, UserDetail } from '../services/user.service';
import { RoleService } from '../services/role.service';
import { HospitalService } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { ImpersonationService } from '../services/impersonation.service';

/**
 * Registration: no username or password is asked (the server generates
 * one-time credentials), the licence number is never required, e-mail is
 * optional for a patient-only registration, and a phone is validated.
 */
describe('UserListComponent registration', () => {
  let fixture: ComponentFixture<UserListComponent>;
  let component: UserListComponent;
  let users: jasmine.SpyObj<UserService>;
  let toast: jasmine.SpyObj<ToastService>;

  const page = { content: [], number: 0, totalPages: 0, totalElements: 0 };

  beforeEach(() => {
    users = jasmine.createSpyObj<UserService>('UserService', [
      'list',
      'search',
      'adminRegister',
      'update',
      'getById',
    ]);
    users.list.and.returnValue(of(page) as never);
    users.search.and.returnValue(of(page) as never);
    users.adminRegister.and.returnValue(of({ id: 'u1' } as UserDetail));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'warning']);

    TestBed.configureTestingModule({
      imports: [UserListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: UserService, useValue: users },
        { provide: RoleService, useValue: { list: () => of([]) } },
        {
          provide: HospitalService,
          useValue: { list: () => of({ content: [] }), getMyHospitalAsResponse: () => of(null) },
        },
        { provide: ToastService, useValue: toast },
        { provide: RoleContextService, useValue: { isSuperAdmin: () => true } },
        { provide: ImpersonationService, useValue: { active: () => null } },
      ],
    });
    fixture = TestBed.createComponent(UserListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  function openCreateWith(roles: string[]): void {
    component.openCreate();
    component.selectedRoles = roles;
    component.createForm.firstName = 'Awa';
    component.createForm.lastName = 'Traoré';
    component.createForm.phoneNumber = '70202020';
    fixture.detectChanges();
  }

  it('never opens the edit dialog on a deleted account (D126)', () => {
    component.openEdit({ id: 'u9', username: 'gone', deleted: true } as never);
    expect(component.editing()).toBeNull();
    expect(users.getById).not.toHaveBeenCalled();
  });

  it('asks for neither username nor password on create', () => {
    openCreateWith(['ROLE_DOCTOR']);
    expect(fixture.debugElement.query(By.css('#username'))).toBeNull();
    expect(fixture.debugElement.query(By.css('#password'))).toBeNull();
  });

  it('shows the licence field without an asterisk or required attribute', () => {
    openCreateWith(['ROLE_DOCTOR']);
    const label = fixture.debugElement.query(By.css('label[for="licenseNumber"]'));
    const input = fixture.debugElement.query(By.css('#licenseNumber'));
    expect(label.nativeElement.textContent).not.toContain('*');
    expect(input.nativeElement.required).toBeFalse();
  });

  it('registers a doctor without licence, username or password', () => {
    openCreateWith(['ROLE_DOCTOR']);
    component.createForm.email = 'awa@hopital.bf';
    component.createForm.username = 'leftover';
    component.createForm.password = 'leftover';
    component.submitCreate();
    expect(users.adminRegister).toHaveBeenCalled();
    const payload = users.adminRegister.calls.mostRecent().args[0];
    expect(payload.username).toBeUndefined();
    expect(payload.password).toBeUndefined();
    expect(payload.licenseNumber).toBeUndefined();
  });

  it('lets a patient register without e-mail but not a staff member', () => {
    openCreateWith(['ROLE_PATIENT']);
    expect(component.emailRequired).toBeFalse();
    component.submitCreate();
    expect(users.adminRegister).toHaveBeenCalledTimes(1);
    expect(users.adminRegister.calls.mostRecent().args[0].email).toBeUndefined();

    openCreateWith(['ROLE_NURSE']);
    expect(component.emailRequired).toBeTrue();
    component.submitCreate();
    expect(users.adminRegister).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalled();
  });

  it('refuses a phone that cannot be a number', () => {
    openCreateWith(['ROLE_PATIENT']);
    component.createForm.phoneNumber = '70-ab';
    component.submitCreate();
    expect(users.adminRegister).not.toHaveBeenCalled();
    expect(component.fieldErrors()['phoneNumber']).toBeTruthy();
  });

  it('shows undeliverable generated credentials once, then forgets them', () => {
    users.adminRegister.and.returnValue(
      of({
        id: 'u1',
        temporaryUsername: 'awa.traore1234',
        temporaryPassword: 'X9!a',
      } as UserDetail),
    );
    openCreateWith(['ROLE_PATIENT']);
    component.submitCreate();
    fixture.detectChanges();
    const shown = fixture.debugElement.query(By.css('[data-test="issued-password"]'));
    expect(shown.nativeElement.textContent).toContain('X9!a');

    component.dismissCredentials();
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.css('[data-test="issued-credentials"]'))).toBeNull();
  });
});

describe('UserListComponent create modal as a hospital admin (QA D189, D195)', () => {
  let fixture: ComponentFixture<UserListComponent>;
  let component: UserListComponent;
  const page = { content: [], number: 0, totalPages: 0, totalElements: 0 };
  const roles = [
    { id: 'r1', code: 'ROLE_SUPER_ADMIN', name: 'ROLE_SUPER_ADMIN' },
    { id: 'r2', code: 'ROLE_HOSPITAL_ADMIN', name: 'HOSPITAL_ADMIN' },
    { id: 'r3', code: 'ROLE_PATIENT', name: 'PATIENT' },
    { id: 'r4', code: 'ROLE_NURSE', name: 'NURSE' },
    { id: 'r5', code: 'ROLE_RECEPTIONIST', name: 'RECEPTIONIST' },
    { id: 'r6', code: 'ROLE_ADMIN', name: 'ADMIN' },
  ];

  beforeEach(() => {
    const users = jasmine.createSpyObj<UserService>('UserService', ['list', 'search']);
    users.list.and.returnValue(of(page) as never);
    users.search.and.returnValue(of(page) as never);
    TestBed.configureTestingModule({
      imports: [UserListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: UserService, useValue: users },
        { provide: RoleService, useValue: { list: () => of(roles) } },
        {
          provide: HospitalService,
          useValue: {
            list: () => of({ content: [] }),
            getMyHospitalAsResponse: () => of({ id: 'h1', name: 'A' }),
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'warning',
          ]),
        },
        { provide: RoleContextService, useValue: { isSuperAdmin: () => false } },
        { provide: ImpersonationService, useValue: { active: () => null } },
      ],
    });
    fixture = TestBed.createComponent(UserListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('offers staff roles only: no super-admin, admin or patient', () => {
    expect(component.grantableRoles().map((r) => r.code)).toEqual([
      'ROLE_NURSE',
      'ROLE_RECEPTIONIST',
    ]);
  });

  it('asks a medical specialization for a doctor, not for a nurse', () => {
    component.openCreate();
    component.onRoleToggle('ROLE_NURSE', true);
    fixture.detectChanges();
    expect(component.specializationApplicable).toBeFalse();
    expect(fixture.debugElement.query(By.css('#specialization'))).toBeNull();

    component.onRoleToggle('ROLE_DOCTOR', true);
    fixture.detectChanges();
    expect(component.specializationApplicable).toBeTrue();
    expect(fixture.debugElement.query(By.css('#specialization'))).not.toBeNull();
  });
});
