import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';
import { of } from 'rxjs';

import { UserListComponent } from './user-list';
import { UserService, UserDetail } from '../services/user.service';
import { RoleResponse, RoleService } from '../services/role.service';
import { HospitalService } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
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
    toast = jasmine.createSpyObj<ToastService>('ToastService', [
      'success',
      'error',
      'warning',
      'info',
    ]);

    TestBed.configureTestingModule({
      imports: [UserListComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AuthService, useValue: { getUserProfile: () => null, getUserId: () => null } },
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

  it('ends the delete confirmation with no doubled question mark (D192)', () => {
    component.confirmDelete({ id: 'u7', firstName: 'Awa', lastName: 'Traoré' } as never);
    fixture.detectChanges();
    const strong = fixture.debugElement.query(By.css('.warning-icon + p strong'));
    expect(strong).not.toBeNull();
    const paragraph = (strong.nativeElement as HTMLElement).parentElement as HTMLElement;
    expect(paragraph.textContent?.trim().endsWith('Traoré')).toBeTrue();
    expect(paragraph.textContent).not.toContain('?');
  });

  it('never opens the edit dialog on a deleted account (D126)', () => {
    component.openEdit({ id: 'u9', username: 'gone', deleted: true } as never);
    expect(component.editing()).toBeNull();
    expect(users.getById).not.toHaveBeenCalled();
  });

  it('D765: the edit dialog lists every role read-only and never sends roles', () => {
    users.getById.and.returnValue(
      of({
        id: 'u5',
        phoneNumber: '+22670202020',
        roles: [{ code: 'ROLE_NURSE' }, { code: 'ROLE_MIDWIFE' }],
      } as unknown as UserDetail),
    );
    users.update.and.returnValue(of({ id: 'u5' } as UserDetail));
    component.openEdit({
      id: 'u5',
      username: 'awa',
      email: 'awa@hopital.bf',
      firstName: 'Awa',
      lastName: 'Traoré',
      roleName: 'ROLE_NURSE',
      deleted: false,
    } as never);
    fixture.detectChanges();
    expect(component.editRoles()).toEqual(['ROLE_NURSE', 'ROLE_MIDWIFE']);
    const chips = fixture.debugElement.queryAll(By.css('[data-testid="edit-roles"] .role-chip'));
    expect(chips.length).toBe(2);
    expect(fixture.debugElement.query(By.css('.role-checkbox input'))).toBeNull();

    component.submitCreate();
    expect(users.update).toHaveBeenCalledTimes(1);
    expect(users.update.calls.mostRecent().args[1].roleNames).toBeUndefined();
  });

  it('QA D838: the edit dialog shows the registered hospital, job title and employment type, read-only', () => {
    users.getById.and.returnValue(
      of({
        id: 'u7',
        phoneNumber: '+22670303030',
        roles: [{ code: 'ROLE_NURSE' }],
        staffHospitalId: 'h-wp',
        staffHospitalName: 'Wend-Panga',
        jobTitle: 'NURSE',
        employmentType: 'FULL_TIME',
      } as unknown as UserDetail),
    );
    users.update.and.returnValue(of({ id: 'u7' } as UserDetail));
    component.openEdit({
      id: 'u7',
      username: 'boukary',
      email: 'b@hopital.bf',
      firstName: 'Boukary',
      lastName: 'Sawadogo',
      roleName: 'ROLE_NURSE',
      deleted: false,
    } as never);
    fixture.detectChanges();

    expect(component.createForm.jobTitle).toBe('NURSE');
    expect(component.createForm.employmentType).toBe('FULL_TIME');
    expect(component.createForm.hospitalId).toBe('h-wp');
    const hospital = fixture.debugElement.query(By.css('[data-testid="edit-staff-hospital"]'));
    expect(hospital.nativeElement.textContent).toContain('Wend-Panga');

    // Editing only the phone sends none of them: PUT /users does not change them.
    component.createForm.phoneNumber = '+22670404040';
    component.submitCreate();
    const sent = users.update.calls.mostRecent().args[1];
    expect(sent.phoneNumber).toBe('+22670404040');
    expect(sent.jobTitle).toBeUndefined();
    expect(sent.employmentType).toBeUndefined();
    expect(sent.hospitalId).toBeUndefined();
  });

  it('D765 path re-verified: create still offers role checkboxes and sends the roles', () => {
    openCreateWith(['ROLE_DOCTOR']);
    component.createForm.email = 'awa@hopital.bf';
    component.submitCreate();
    expect(users.adminRegister.calls.mostRecent().args[0].roleNames).toEqual(['ROLE_DOCTOR']);
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

  it('QA D522: a multi-role registration says the activation went out by e-mail', () => {
    users.adminRegister.and.returnValue(
      of({
        id: 'u1',
        activationDelivery: [
          { channel: 'SMS', purpose: 'ACTIVATION', outcome: 'FAILED' },
          { channel: 'EMAIL', purpose: 'ACTIVATION', outcome: 'QUEUED' },
        ],
      } as unknown as UserDetail),
    );
    openCreateWith(['ROLE_DOCTOR', 'ROLE_NURSE']);
    component.createForm.email = 'awa@hopital.bf';
    component.submitCreate();
    expect(toast.warning).toHaveBeenCalledWith('DELIVERY.SMS_FAILED');
    expect(toast.info).toHaveBeenCalledWith('DELIVERY.ACTIVATION_EMAIL_QUEUED');
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
        { provide: AuthService, useValue: { getUserProfile: () => null, getUserId: () => null } },
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

  it('QA D626: never offers the patient role, however the row names it, even to a super-admin', () => {
    const row = (code: string, name: string, authority = '') =>
      ({ id: code + name, code, name, authority }) as unknown as RoleResponse;
    component.availableRoles.set([
      row('', 'ROLE_PATIENT'),
      row('PATIENT', 'Patient'),
      row('', 'Patient(e)', 'ROLE_PATIENT'),
      row('ROLE_NURSE', 'ROLE_NURSE'),
    ]);
    expect(component.grantableRoles().map((r) => r.code)).toEqual(['ROLE_NURSE']);
    (
      TestBed.inject(RoleContextService) as unknown as { isSuperAdmin: () => boolean }
    ).isSuperAdmin = () => true;
    component.availableRoles.set([...component.availableRoles()]);
    expect(component.grantableRoles().map((r) => r.code)).toEqual(['ROLE_NURSE']);
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

describe('UserListComponent onboarding as a hospital admin (D201)', () => {
  it('sends the admin hospital after the modal resets the form', () => {
    const users = jasmine.createSpyObj<UserService>('UserService', [
      'list',
      'search',
      'adminRegister',
      'update',
      'getById',
    ]);
    const page = { content: [], number: 0, totalPages: 0, totalElements: 0 };
    users.list.and.returnValue(of(page) as never);
    users.search.and.returnValue(of(page) as never);
    users.adminRegister.and.returnValue(of({ id: 'u1' } as UserDetail));
    TestBed.configureTestingModule({
      imports: [UserListComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AuthService, useValue: { getUserProfile: () => null, getUserId: () => null } },
        provideRouter([]),
        { provide: UserService, useValue: users },
        { provide: RoleService, useValue: { list: () => of([]) } },
        {
          provide: HospitalService,
          useValue: {
            list: () => of([]),
            getMyHospitalAsResponse: () => of({ id: 'h-a', name: 'QA Hôpital A' }),
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'warning']),
        },
        { provide: RoleContextService, useValue: { isSuperAdmin: () => false } },
        { provide: ImpersonationService, useValue: { active: () => null } },
      ],
    });
    const fixture = TestBed.createComponent(UserListComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    component.openCreate();
    component.selectedRoles = ['ROLE_NURSE'];
    component.createForm.firstName = 'Awa';
    component.createForm.lastName = 'Traoré';
    component.createForm.email = 'awa@hopital.bf';
    component.createForm.phoneNumber = '70202020';
    component.submitCreate();

    expect(users.adminRegister).toHaveBeenCalled();
    expect(users.adminRegister.calls.mostRecent().args[0].hospitalId).toBe('h-a');
  });
});

/** QA D528/D663 replay: HOSPITAL_ADMIN_A opens /users in FR, then ES; the Profil column read STAFF / PATIENT. */
describe('UserListComponent — the Profil column is labelled (QA D528/D663)', () => {
  function cellsIn(lang: 'fr' | 'es'): string[] {
    const page = {
      content: [
        { id: 'u1', firstName: 'Awa', lastName: 'Traoré', profileType: 'STAFF', roles: [] },
        { id: 'u2', firstName: 'Ali', lastName: 'Sow', profileType: 'PATIENT', roles: [] },
      ],
      number: 0,
      totalPages: 1,
      totalElements: 2,
    };
    const users = jasmine.createSpyObj<UserService>('UserService', ['list', 'search']);
    users.list.and.returnValue(of(page) as never);
    users.search.and.returnValue(of(page) as never);
    TestBed.configureTestingModule({
      imports: [UserListComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AuthService, useValue: { getUserProfile: () => null, getUserId: () => null } },
        provideRouter([]),
        { provide: UserService, useValue: users },
        { provide: RoleService, useValue: { list: () => of([]) } },
        {
          provide: HospitalService,
          useValue: { list: () => of({ content: [] }), getMyHospitalAsResponse: () => of(null) },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'warning', 'info']),
        },
        { provide: RoleContextService, useValue: { isSuperAdmin: () => false } },
        { provide: ImpersonationService, useValue: { active: () => null } },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.setTranslation('es', es);
    translate.use(lang);
    const fixture = TestBed.createComponent(UserListComponent);
    fixture.detectChanges();
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('tbody td')).map(
      (td) => td.textContent?.trim() ?? '',
    );
  }

  it('shows Personnel and Patient(e) in French, never STAFF or PATIENT', () => {
    const cells = cellsIn('fr');
    expect(cells).toContain('Personnel');
    expect(cells).toContain('Patient(e)');
    expect(cells).not.toContain('STAFF');
    expect(cells).not.toContain('PATIENT');
  });

  it('shows Personal and Paciente in Spanish', () => {
    const cells = cellsIn('es');
    expect(cells).toContain('Personal');
    expect(cells).toContain('Paciente');
    expect(cells).not.toContain('STAFF');
  });
});

/** QA D818: a new user's role is never granted at an archived hospital. */
describe('UserListComponent — no archived hospital in the create picker (QA D818)', () => {
  it('lists only open hospitals for a super-admin', () => {
    const users = jasmine.createSpyObj<UserService>('UserService', ['list', 'search']);
    users.list.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }) as never,
    );
    TestBed.configureTestingModule({
      imports: [UserListComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AuthService, useValue: { getUserProfile: () => null, getUserId: () => null } },
        provideRouter([]),
        { provide: UserService, useValue: users },
        { provide: RoleService, useValue: { list: () => of([]) } },
        {
          provide: HospitalService,
          useValue: {
            list: () =>
              of([
                { id: 'h1', name: 'CMA Pissy', lifecycleState: 'ACTIVE' },
                { id: 'h2', name: 'Ancien CMA', lifecycleState: 'ARCHIVED' },
                { id: 'h3', name: 'CMA à purger', lifecycleState: 'PENDING_PURGE' },
              ]),
            getMyHospitalAsResponse: () => of(null),
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
        { provide: RoleContextService, useValue: { isSuperAdmin: () => true } },
        { provide: ImpersonationService, useValue: { active: () => null } },
      ],
    });
    const component = TestBed.createComponent(UserListComponent).componentInstance;
    component.loadHospitals();
    expect(component.availableHospitals().map((h) => h.id)).toEqual(['h1']);
  });
});
