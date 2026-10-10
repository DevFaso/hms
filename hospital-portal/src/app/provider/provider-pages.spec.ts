import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, of, throwError } from 'rxjs';

import { ProviderContextService } from '../core/provider-context.service';
import { ToastService } from '../core/toast.service';
import {
  ProviderAuditPage,
  ProviderProfile,
  ProviderSettings,
  ProviderStaffMember,
} from '../services/provider.model';
import { ProviderPortalService } from '../services/provider-portal.service';
import { UserDetail, UserService } from '../services/user.service';
import { settingsFixture } from '../testing/provider-fixtures';
import { ProviderAuditComponent } from './provider-audit/provider-audit';
import { ProviderHomeComponent } from './provider-home/provider-home';
import { ProviderProfileComponent } from './provider-profile/provider-profile';
import { ProviderStaffRegisterComponent } from './provider-staff-register/provider-staff-register';
import { ProviderStaffComponent } from './provider-staff/provider-staff';

const NOT_AVAILABLE = new HttpErrorResponse({ status: 404 });

function profile(overrides: Partial<ProviderProfile> = {}): ProviderProfile {
  return {
    id: 'f-1',
    facilityType: 'PHARMACY',
    code: 'PH-1',
    phoneNumber: '+22670000000',
    email: null,
    website: null,
    name: 'Pharmacie du Marché',
    address: 'Secteur 12',
    city: 'Ouagadougou',
    region: 'Centre',
    legalName: 'Pharmacie du Marché SARL',
    tradeName: null,
    licenceNumber: 'LIC-77',
    licenceAuthority: 'DGPML',
    companyPhone: '+22625000000',
    verifiedAt: '2026-10-08T12:00:00',
    verificationStatus: 'VERIFIED',
    editable: true,
    ...overrides,
  };
}

function member(overrides: Partial<ProviderStaffMember> = {}): ProviderStaffMember {
  return {
    userId: 'u-2',
    username: 'pharm',
    firstName: 'Ali',
    lastName: 'Traoré',
    email: 'ali@pharmacy.test',
    roles: ['PHARMACIST'],
    active: true,
    invitationPending: false,
    providerAdmin: false,
    self: false,
    ...overrides,
  };
}

describe('provider shell pages', () => {
  let portal: jasmine.SpyObj<ProviderPortalService>;
  let toast: jasmine.SpyObj<ToastService>;
  let settings: ProviderSettings | null;

  function configure(): void {
    TestBed.configureTestingModule({
      imports: [TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: ProviderPortalService, useValue: portal },
        { provide: ToastService, useValue: toast },
        {
          provide: ProviderContextService,
          useValue: { settings: () => settings, load: () => of(settings) },
        },
      ],
    });
  }

  function render<T>(component: Type<T>): { cmp: T; host: () => HTMLElement } {
    configure();
    const fixture = TestBed.createComponent(component);
    fixture.detectChanges();
    return {
      cmp: fixture.componentInstance,
      host: () => {
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
      },
    };
  }

  beforeEach(() => {
    settings = settingsFixture();
    portal = jasmine.createSpyObj<ProviderPortalService>('ProviderPortalService', [
      'profile',
      'updateProfile',
      'staff',
      'deactivate',
      'activate',
      'audit',
    ]);
    portal.profile.and.returnValue(of(profile()));
    portal.updateProfile.and.callFake((body) => of(profile({ phoneNumber: body.phoneNumber })));
    portal.staff.and.returnValue(
      of([
        member(),
        member({ userId: 'u-1', self: true, providerAdmin: true, roles: ['PROVIDER_ADMIN'] }),
      ]),
    );
    portal.deactivate.and.returnValue(of(member({ active: false })));
    portal.activate.and.returnValue(
      of(
        member({
          active: false,
          invitationPending: true,
          activationDelivery: [{ channel: 'EMAIL', outcome: 'QUEUED' }],
        }),
      ),
    );
    portal.audit.and.returnValue(
      of({
        entries: [
          {
            id: 'a-1',
            eventTimestamp: '2026-10-09T10:00:00',
            eventType: 'DATA_UPDATE',
            status: 'SUCCESS',
            actorUserId: 'u-1',
            actorName: 'padmin',
            roleName: 'PROVIDER_ADMIN',
            entityType: 'PROVIDER_FACILITY',
            resourceId: 'f-1',
          },
        ],
        page: 0,
        size: 20,
        totalElements: 21,
        hasMore: true,
      } as ProviderAuditPage),
    );
    toast = jasmine.createSpyObj<ToastService>('ToastService', [
      'success',
      'warning',
      'error',
      'info',
    ]);
  });

  afterEach(() => TestBed.resetTestingModule());

  // ── home ──────────────────────────────────────────────────────────────

  it('home names the facility and links to the pages this user may open', () => {
    const { cmp, host } = render(ProviderHomeComponent);
    expect(cmp.links().map((l) => l.route)).toEqual([
      '/provider/profile',
      '/provider/staff',
      '/provider/audit',
    ]);
    expect(host().querySelectorAll('[data-test="home-link"]').length).toBe(3);
    expect(host().querySelector('[data-test="unverified-note"]')).toBeNull();
  });

  it('home for a pharmacist: profile only; an unverified facility says so', () => {
    settings = settingsFixture({ providerAdmin: false });
    portal.profile.and.returnValue(
      of(profile({ verificationStatus: 'SUBMITTED', verifiedAt: null })),
    );
    const { cmp, host } = render(ProviderHomeComponent);
    expect(cmp.links().map((l) => l.route)).toEqual(['/provider/profile']);
    expect(host().querySelector('[data-test="unverified-note"]')).not.toBeNull();
  });

  it('home shows the calm not-available state on a 404, and a load failure otherwise', () => {
    portal.profile.and.returnValue(throwError(() => NOT_AVAILABLE));
    const notAvailable = render(ProviderHomeComponent);
    expect(notAvailable.host().querySelector('[data-test="not-available"]')).not.toBeNull();
    TestBed.resetTestingModule();

    portal.profile.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    const failed = render(ProviderHomeComponent);
    expect(failed.host().querySelector('[data-test="load-failed"]')).not.toBeNull();
  });

  // ── profile ───────────────────────────────────────────────────────────

  it('profile: the admin edits the contact; the verified identity is read-only', () => {
    const { cmp, host } = render(ProviderProfileComponent);
    expect(host().querySelector('#profile-phone')).not.toBeNull();
    expect(host().querySelector('[data-test="identity"]')).not.toBeNull();

    cmp.phoneNumber.set(' +22670112233 ');
    cmp.email.set(' ');
    cmp.website.set('https://pharmacy.test');
    cmp.save();

    expect(portal.updateProfile).toHaveBeenCalledWith({
      phoneNumber: '+22670112233',
      email: null,
      website: 'https://pharmacy.test',
    });
    expect(toast.success).toHaveBeenCalled();
    expect(cmp.phoneNumber()).toBe('+22670112233');
  });

  it('profile: a staff member reads the contact; no identity before verification', () => {
    portal.profile.and.returnValue(of(profile({ editable: false, verifiedAt: null })));
    const { cmp, host } = render(ProviderProfileComponent);
    expect(host().querySelector('#profile-phone')).toBeNull();
    expect(host().querySelector('[data-test="contact-readonly"]')).not.toBeNull();
    expect(host().querySelector('[data-test="identity-pending"]')).not.toBeNull();
    cmp.phoneNumber.set('');
    cmp.save();
    expect(portal.updateProfile).not.toHaveBeenCalled();
  });

  it('profile: a refused save shows the server message; a 404 turns the page not-available', () => {
    portal.updateProfile.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { message: 'Bad phone' } })),
    );
    const { cmp, host } = render(ProviderProfileComponent);
    cmp.save();
    expect(cmp.saveError()).toBe('Bad phone');
    expect(host().querySelector('[data-test="save-error"]')).not.toBeNull();

    portal.updateProfile.and.returnValue(throwError(() => NOT_AVAILABLE));
    cmp.save();
    expect(cmp.notAvailable()).toBeTrue();
  });

  it('profile: a 404 on load is the calm not-available state', () => {
    portal.profile.and.returnValue(throwError(() => NOT_AVAILABLE));
    const { cmp } = render(ProviderProfileComponent);
    expect(cmp.notAvailable()).toBeTrue();
  });

  // ── staff ─────────────────────────────────────────────────────────────

  it('staff: lists members; no action on the admin themself or a peer administrator', () => {
    const { cmp, host } = render(ProviderStaffComponent);
    expect(host().querySelectorAll('[data-test="staff-row"]').length).toBe(2);
    expect(cmp.manageable(member())).toBeTrue();
    expect(cmp.manageable(member({ self: true }))).toBeFalse();
    expect(cmp.manageable(member({ providerAdmin: true }))).toBeFalse();
    expect(host().querySelectorAll('[data-test="deactivate"]').length).toBe(1);
    expect(cmp.staffRoles()).toEqual(['PHARMACIST']);
    expect(cmp.displayName(member({ firstName: null, lastName: null }))).toBe('pharm');
  });

  it('staff: deactivate asks first, then replaces the row', () => {
    const { cmp } = render(ProviderStaffComponent);
    cmp.askDeactivate(member());
    cmp.cancelDeactivate();
    expect(portal.deactivate).not.toHaveBeenCalled();

    cmp.askDeactivate(member());
    cmp.deactivate();
    expect(portal.deactivate).toHaveBeenCalledWith('u-2');
    expect(cmp.staff().find((m) => m.userId === 'u-2')?.active).toBeFalse();
    expect(toast.success).toHaveBeenCalled();
  });

  it('staff: re-invite reports where the code went', () => {
    const { cmp } = render(ProviderStaffComponent);
    cmp.reinvite(member({ active: false }));
    expect(portal.activate).toHaveBeenCalledWith('u-2');
    expect(toast.success).toHaveBeenCalled();

    portal.activate.and.returnValue(
      of(member({ active: false, activationDelivery: [{ channel: 'SMS', outcome: 'FAILED' }] })),
    );
    cmp.reinvite(member({ active: false }));
    expect(toast.info).toHaveBeenCalled();
    expect(toast.warning).toHaveBeenCalled();
  });

  it('staff: a refused action answers like a miss: the list is re-read, never an error page', () => {
    portal.deactivate.and.returnValue(throwError(() => NOT_AVAILABLE));
    const { cmp } = render(ProviderStaffComponent);
    cmp.askDeactivate(member());
    cmp.deactivate();
    expect(toast.info).toHaveBeenCalled();
    expect(portal.staff).toHaveBeenCalledTimes(2);
    expect(cmp.confirmDeactivate()).toBeNull();

    portal.activate.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    cmp.reinvite(member({ active: false }));
    expect(toast.error).toHaveBeenCalled();
    expect(cmp.busyUserId()).toBeNull();
  });

  it('staff: registering reloads the list; a 404 on load is not-available', () => {
    const { cmp, host } = render(ProviderStaffComponent);
    cmp.openRegister();
    expect(host().querySelector('app-provider-staff-register')).not.toBeNull();
    cmp.onRegistered({ activationDelivery: [] } as unknown as UserDetail);
    expect(cmp.registerOpen()).toBeFalse();
    expect(portal.staff).toHaveBeenCalledTimes(2);
    cmp.openRegister();
    cmp.closeRegister();
    expect(cmp.registerOpen()).toBeFalse();
    TestBed.resetTestingModule();

    portal.staff.and.returnValue(throwError(() => NOT_AVAILABLE));
    const unavailable = render(ProviderStaffComponent);
    expect(unavailable.host().querySelector('[data-test="not-available"]')).not.toBeNull();
  });

  // ── audit ─────────────────────────────────────────────────────────────

  it('audit: lists the trail and pages while the server has more', () => {
    const { cmp, host } = render(ProviderAuditComponent);
    expect(host().querySelectorAll('[data-test="audit-row"]').length).toBe(1);
    expect(cmp.recordKey('PROVIDER_FACILITY')).toBe('PROVIDER.AUDIT.RECORD_FACILITY');
    expect(cmp.recordKey('PROVIDER_STAFF')).toBe('PROVIDER.AUDIT.RECORD_STAFF');
    expect(cmp.recordKey('USER')).toBe('PROVIDER.AUDIT.RECORD_ACCOUNT');
    expect(cmp.recordKey('ASSIGNMENT')).toBe('PROVIDER.AUDIT.RECORD_ASSIGNMENT');
    expect(cmp.recordKey(null)).toBe('PROVIDER.AUDIT.RECORD_OTHER');

    cmp.previousPage();
    expect(portal.audit).toHaveBeenCalledTimes(1);
    cmp.nextPage();
    expect(portal.audit).toHaveBeenCalledWith(1, 20);
    cmp.previousPage();
    expect(portal.audit).toHaveBeenCalledWith(0, 20);
  });

  it('audit: empty, not-available and failed states', () => {
    portal.audit.and.returnValue(
      of({ entries: [], page: 0, size: 20, totalElements: 0, hasMore: false }),
    );
    const empty = render(ProviderAuditComponent);
    expect(empty.host().querySelector('[data-test="empty"]')).not.toBeNull();
    expect(empty.cmp.hasNext()).toBeFalse();
    empty.cmp.nextPage();
    expect(portal.audit).toHaveBeenCalledTimes(1);
    TestBed.resetTestingModule();

    portal.audit.and.returnValue(throwError(() => NOT_AVAILABLE));
    expect(render(ProviderAuditComponent).cmp.notAvailable()).toBeTrue();
    TestBed.resetTestingModule();

    portal.audit.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    expect(render(ProviderAuditComponent).cmp.loadFailed()).toBeTrue();
  });
});

describe('ProviderStaffRegisterComponent', () => {
  let users: jasmine.SpyObj<UserService>;

  function render(roles: string[]): ProviderStaffRegisterComponent {
    TestBed.configureTestingModule({
      imports: [ProviderStaffRegisterComponent, TranslateModule.forRoot()],
      providers: [{ provide: UserService, useValue: users }],
    });
    const fixture = TestBed.createComponent(ProviderStaffRegisterComponent);
    fixture.componentRef.setInput('facilityId', 'f-1');
    fixture.componentRef.setInput('facilityName', 'Pharmacie');
    fixture.componentRef.setInput('roles', roles);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  function fill(cmp: ProviderStaffRegisterComponent): void {
    cmp.firstName.set(' Ali ');
    cmp.lastName.set('Traoré');
    cmp.username.set('ali');
    cmp.email.set('ali@pharmacy.test');
    cmp.phoneNumber.set('+22670000000');
  }

  beforeEach(() => {
    users = jasmine.createSpyObj<UserService>('UserService', ['adminRegister']);
    users.adminRegister.and.returnValue(of({ id: 'u-9' } as UserDetail) as Observable<UserDetail>);
  });

  afterEach(() => TestBed.resetTestingModule());

  it('registers at the facility, in the chosen role, with the licence a pharmacist needs', () => {
    const cmp = render(['PHARMACIST']);
    let registered: UserDetail | undefined;
    cmp.registered.subscribe((u) => (registered = u));
    fill(cmp);
    expect(cmp.needsLicence()).toBeTrue();
    expect(cmp.complete()).toBeFalse();
    cmp.submit();
    expect(cmp.errorKey()).toBe('PROVIDER.REGISTER.REQUIRED_FIELDS');
    expect(users.adminRegister).not.toHaveBeenCalled();

    cmp.licenseNumber.set('ONP-9');
    cmp.submit();

    expect(users.adminRegister).toHaveBeenCalledWith({
      firstName: 'Ali',
      lastName: 'Traoré',
      username: 'ali',
      email: 'ali@pharmacy.test',
      phoneNumber: '+22670000000',
      roleNames: ['PHARMACIST'],
      hospitalId: 'f-1',
      licenseNumber: 'ONP-9',
    });
    expect(registered?.id).toBe('u-9');
  });

  it('the platform registers a facility admin without a licence; a refusal shows the server message', () => {
    users.adminRegister.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ status: 400, error: { message: 'Username taken' } }),
      ),
    );
    const cmp = render(['PROVIDER_ADMIN', 'PHARMACIST']);
    expect(cmp.role()).toBe('PROVIDER_ADMIN');
    expect(cmp.needsLicence()).toBeFalse();
    fill(cmp);
    cmp.submit();
    expect(users.adminRegister.calls.mostRecent().args[0].licenseNumber).toBeUndefined();
    expect(cmp.errorText()).toBe('Username taken');
    expect(cmp.saving()).toBeFalse();
  });

  it('close is ignored while saving', () => {
    const cmp = render(['PHARMACIST']);
    let closed = 0;
    cmp.closed.subscribe(() => closed++);
    cmp.saving.set(true);
    cmp.close();
    expect(closed).toBe(0);
    cmp.saving.set(false);
    cmp.close();
    expect(closed).toBe(1);
  });
});
