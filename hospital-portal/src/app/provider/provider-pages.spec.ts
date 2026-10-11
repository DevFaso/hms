import { Type, WritableSignal, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, Subject, of, throwError } from 'rxjs';

import { ProviderContextService, ProviderContextStatus } from '../core/provider-context.service';
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
    canDeactivate: true,
    canReinvite: false,
    ...overrides,
  };
}

describe('provider shell pages', () => {
  let portal: jasmine.SpyObj<ProviderPortalService>;
  let toast: jasmine.SpyObj<ToastService>;
  let settings: ProviderSettings | null;
  /** What the context holds while a page is open: a signal, so a scope switch is seen. */
  let settingsSignal: WritableSignal<ProviderSettings | null>;
  let invalidate: jasmine.Spy;
  /** Forces the context status (none, error); otherwise it follows the settings. */
  let statusOverride: WritableSignal<ProviderContextStatus | null>;

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
          useFactory: () => {
            settingsSignal = signal(settings);
            statusOverride = signal<ProviderContextStatus | null>(null);
            invalidate = jasmine.createSpy('invalidate');
            const status = computed<ProviderContextStatus>(
              () => statusOverride() ?? (settingsSignal() ? 'provider' : 'pending'),
            );
            return {
              settings: settingsSignal,
              status,
              load: () => of(settingsSignal()),
              invalidate,
            };
          },
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
            subjectRole: null,
            impersonatorUserId: null,
            impersonatorUsername: null,
          },
          {
            id: 'a-2',
            eventTimestamp: '2026-10-09T09:00:00',
            eventType: 'DATA_UPDATE',
            status: 'SUCCESS',
            actorUserId: 'u-2',
            actorName: 'pharm',
            roleName: 'PHARMACIST',
            entityType: 'USER_ROLE_ASSIGNMENT',
            resourceId: 'a-9',
            subjectRole: 'PHARMACIST',
            impersonatorUserId: 'sa-1',
            impersonatorUsername: 'support-agent',
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
    // The seat went away: the context is told, so the nav and the guards ask again.
    expect(invalidate).toHaveBeenCalledTimes(1);
    TestBed.resetTestingModule();

    portal.profile.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    const failed = render(ProviderHomeComponent);
    expect(failed.host().querySelector('[data-test="load-failed"]')).not.toBeNull();
  });

  it('staff: losing the admin role at the same facility reads the page again (and its 404 clears it)', () => {
    const { cmp, host } = render(ProviderStaffComponent);
    expect(portal.staff).toHaveBeenCalledTimes(1);

    // A re-ask: same facility, no longer admin. The page reads again.
    portal.staff.and.returnValue(throwError(() => NOT_AVAILABLE));
    settingsSignal.set(settingsFixture({ providerAdmin: false }));
    host();

    expect(portal.staff).toHaveBeenCalledTimes(2);
    expect(cmp.notAvailable()).toBeTrue();
    expect(cmp.staff()).toEqual([]);

    // The same answer again reloads nothing: a key change, never a loop.
    settingsSignal.set(settingsFixture({ providerAdmin: false }));
    host();
    expect(portal.staff).toHaveBeenCalledTimes(2);
  });

  it('home: follows the facility; a re-ask of the same facility does not reload; stale answers drop', () => {
    const lateA = new Subject<ProviderProfile>();
    portal.profile.and.returnValue(lateA);
    const { cmp, host } = render(ProviderHomeComponent);
    portal.profile.and.returnValue(of(profile({ id: 'f-2', name: 'Branch B' })));

    // Between answers (null), then the same facility again: no reload.
    settingsSignal.set(null);
    host();
    settingsSignal.set(settingsFixture());
    host();
    expect(portal.profile).toHaveBeenCalledTimes(1);

    settingsSignal.set(settingsFixture({ facilityId: 'f-2' }));
    host();
    lateA.next(profile({ name: 'Branch A' }));
    lateA.error(NOT_AVAILABLE);

    expect(cmp.profile()?.name).toBe('Branch B');
    expect(cmp.notAvailable()).toBeFalse();
    expect(invalidate).not.toHaveBeenCalled();
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

  it('profile: a branch switch discards the edit; no PUT ever carries the old branch contact', () => {
    const branchB = new Subject<ProviderProfile>();
    const { cmp, host } = render(ProviderProfileComponent);
    cmp.phoneNumber.set('+22670999999');
    portal.profile.and.returnValue(branchB);

    settingsSignal.set(settingsFixture({ facilityId: 'f-2' }));
    host();

    // B is loading: the A edit is gone and nothing can be saved.
    expect(cmp.canSave()).toBeFalse();
    cmp.save();
    expect(portal.updateProfile).not.toHaveBeenCalled();

    branchB.next(profile({ id: 'f-2', phoneNumber: '+22670222222' }));
    branchB.complete();
    expect(cmp.phoneNumber()).toBe('+22670222222');
    cmp.save();
    expect(portal.updateProfile).toHaveBeenCalledOnceWith({
      phoneNumber: '+22670222222',
      email: null,
      website: null,
    });
  });

  it('profile: an answer for the branch the page has left is never shown', () => {
    const branchA = new Subject<ProviderProfile>();
    portal.profile.and.returnValue(branchA);
    const { cmp, host } = render(ProviderProfileComponent);
    portal.profile.and.returnValue(of(profile({ id: 'f-2', phoneNumber: '+22670222222' })));

    settingsSignal.set(settingsFixture({ facilityId: 'f-2' }));
    host();
    branchA.next(profile({ phoneNumber: '+22670111111' }));

    expect(cmp.profile()?.id).toBe('f-2');
    expect(cmp.phoneNumber()).toBe('+22670222222');
  });

  it('profile: a save answer arriving after a branch switch is dropped, error or not', () => {
    const put = new Subject<ProviderProfile>();
    portal.updateProfile.and.returnValue(put);
    const { cmp, host } = render(ProviderProfileComponent);
    cmp.save();
    portal.profile.and.returnValue(of(profile({ id: 'f-2', phoneNumber: '+22670222222' })));

    settingsSignal.set(settingsFixture({ facilityId: 'f-2' }));
    host();
    put.next(profile({ phoneNumber: '+22670111111' }));

    expect(cmp.phoneNumber()).toBe('+22670222222');
    expect(toast.success).not.toHaveBeenCalled();

    const failing = new Subject<ProviderProfile>();
    portal.updateProfile.and.returnValue(failing);
    cmp.save();
    settingsSignal.set(settingsFixture({ facilityId: 'f-3' }));
    host();
    failing.error(new HttpErrorResponse({ status: 400, error: { message: 'Bad phone' } }));
    expect(cmp.saveError()).toBeNull();
    expect(cmp.saving()).toBeFalse();
  });

  it('profile: a load failure for a branch the page has left is dropped', () => {
    const lateA = new Subject<ProviderProfile>();
    portal.profile.and.returnValue(lateA);
    const { cmp, host } = render(ProviderProfileComponent);
    portal.profile.and.returnValue(of(profile({ id: 'f-2' })));
    settingsSignal.set(settingsFixture({ facilityId: 'f-2' }));
    host();
    lateA.error(NOT_AVAILABLE);
    expect(cmp.notAvailable()).toBeFalse();
    expect(cmp.loadFailed()).toBeFalse();
  });

  it('profile: a 404 on load is the calm not-available state', () => {
    portal.profile.and.returnValue(throwError(() => NOT_AVAILABLE));
    const { cmp } = render(ProviderProfileComponent);
    expect(cmp.notAvailable()).toBeTrue();
    expect(invalidate).toHaveBeenCalled();
  });

  // ── staff ─────────────────────────────────────────────────────────────

  it('staff: renders the actions the server says it would accept, and nothing else', () => {
    portal.staff.and.returnValue(
      of([
        member({ userId: 'u-2', canDeactivate: true, canReinvite: false }),
        member({
          userId: 'u-3',
          active: false,
          invitationPending: true,
          canDeactivate: true,
          canReinvite: true,
        }),
        // Inactive with no invitation out: nothing to revoke, a new code to send.
        member({ userId: 'u-5', active: false, canDeactivate: false, canReinvite: true }),
        // An admin elsewhere, or a disabled account: the server offers nothing.
        member({ userId: 'u-4', active: false, canDeactivate: false, canReinvite: false }),
        member({ userId: 'u-1', self: true, providerAdmin: true, canDeactivate: false }),
      ]),
    );
    const { cmp, host } = render(ProviderStaffComponent);
    expect(host().querySelectorAll('[data-test="staff-row"]').length).toBe(5);
    expect(host().querySelectorAll('[data-test="deactivate"]').length).toBe(2);
    expect(host().querySelectorAll('[data-test="reinvite"]').length).toBe(2);
    expect(cmp.staffRoles()).toEqual(['PHARMACIST']);
    expect(cmp.displayName(member({ firstName: null, lastName: null }))).toBe('pharm');

    // Not offered: never sent either.
    cmp.askDeactivate(member({ userId: 'u-4', canDeactivate: false }));
    expect(cmp.confirmDeactivate()).toBeNull();
    cmp.reinvite(member({ userId: 'u-4', canReinvite: false }));
    expect(portal.activate).not.toHaveBeenCalled();
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
    cmp.reinvite(member({ active: false, canReinvite: true }));
    expect(portal.activate).toHaveBeenCalledWith('u-2');
    expect(toast.success).toHaveBeenCalled();

    portal.activate.and.returnValue(
      of(member({ active: false, activationDelivery: [{ channel: 'SMS', outcome: 'FAILED' }] })),
    );
    cmp.reinvite(member({ active: false, canReinvite: true }));
    expect(toast.info).toHaveBeenCalled();
    expect(toast.warning).toHaveBeenCalled();
  });

  it('staff: each row waits on its own; no double request; a failure clears only its row', () => {
    const first = new Subject<ProviderStaffMember>();
    const second = new Subject<ProviderStaffMember>();
    portal.activate.and.returnValues(first, second);
    const { cmp } = render(ProviderStaffComponent);
    const a = member({ userId: 'u-a', active: false, canReinvite: true });
    const b = member({ userId: 'u-b', active: false, canReinvite: true });

    cmp.reinvite(a);
    cmp.reinvite(a);
    cmp.reinvite(b);
    expect(portal.activate.calls.allArgs()).toEqual([['u-a'], ['u-b']]);
    expect(cmp.isBusy(a)).toBeTrue();
    expect(cmp.isBusy(b)).toBeTrue();

    second.error(new HttpErrorResponse({ status: 500 }));
    expect(cmp.isBusy(b)).toBeFalse();
    expect(cmp.isBusy(a)).toBeTrue();
    expect(toast.error).toHaveBeenCalled();

    first.next(member({ userId: 'u-a', active: false, invitationPending: true }));
    first.complete();
    expect(cmp.isBusy(a)).toBeFalse();
  });

  it('staff: a refused action answers like a miss: the list is re-read, never an error page', () => {
    portal.deactivate.and.returnValue(throwError(() => NOT_AVAILABLE));
    const { cmp } = render(ProviderStaffComponent);
    cmp.askDeactivate(member());
    cmp.deactivate();
    expect(toast.info).toHaveBeenCalled();
    expect(portal.staff).toHaveBeenCalledTimes(2);
    expect(cmp.confirmDeactivate()).toBeNull();
    expect(cmp.isBusy(member())).toBeFalse();
  });

  it('staff: a switch to another facility re-reads the list and closes the dialogs', () => {
    const { cmp, host } = render(ProviderStaffComponent);
    expect(portal.staff).toHaveBeenCalledTimes(1);
    cmp.openRegister();
    cmp.askDeactivate(member());

    settingsSignal.set(settingsFixture({ facilityId: 'f-2' }));
    host();

    expect(cmp.facilityId()).toBe('f-2');
    expect(portal.staff).toHaveBeenCalledTimes(2);
    expect(cmp.registerOpen()).toBeFalse();
    expect(cmp.confirmDeactivate()).toBeNull();
  });

  it('staff: an action answer arriving after a branch switch is dropped, never applied or toasted', () => {
    const late = new Subject<ProviderStaffMember>();
    portal.activate.and.returnValue(late);
    const { cmp, host } = render(ProviderStaffComponent);
    cmp.reinvite(member({ active: false, canReinvite: true }));
    portal.staff.and.returnValue(of([member({ userId: 'u-9', username: 'branch-b' })]));

    settingsSignal.set(settingsFixture({ facilityId: 'f-2' }));
    host();
    late.next(member({ active: false, invitationPending: true }));
    late.complete();

    expect(cmp.staff().map((m) => m.userId)).toEqual(['u-9']);
    expect(toast.success).not.toHaveBeenCalled();
    expect(toast.info).not.toHaveBeenCalled();
  });

  it('staff: a list or action failure about the branch the page has left is dropped', () => {
    const lateList = new Subject<ProviderStaffMember[]>();
    const lateAction = new Subject<ProviderStaffMember>();
    const { cmp, host } = render(ProviderStaffComponent);
    portal.deactivate.and.returnValue(lateAction);
    cmp.askDeactivate(member());
    cmp.deactivate();
    portal.staff.and.returnValue(lateList);
    cmp.load();
    portal.staff.and.returnValue(of([member({ userId: 'u-9' })]));

    settingsSignal.set(settingsFixture({ facilityId: 'f-2' }));
    host();
    lateList.error(NOT_AVAILABLE);
    lateAction.error(NOT_AVAILABLE);

    expect(cmp.notAvailable()).toBeFalse();
    expect(invalidate).not.toHaveBeenCalled();
    expect(toast.info).not.toHaveBeenCalled();
    expect(cmp.staff().map((m) => m.userId)).toEqual(['u-9']);
  });

  it('staff: a branch switch while a registration is in flight never loses its result', () => {
    const { cmp, host } = render(ProviderStaffComponent);
    cmp.openRegister();
    expect(cmp.registerFacilityId()).toBe('f-1');
    cmp.onRegisterSubmitting(true);

    settingsSignal.set(settingsFixture({ facilityId: 'f-2' }));
    host();
    // Still open: destroying it would drop the toast, the warnings and the refresh.
    expect(cmp.registerOpen()).toBeTrue();
    expect(cmp.registerFacilityId()).toBe('f-1');

    cmp.onRegisterSubmitting(false);
    cmp.onRegistered({
      activationDelivery: [{ channel: 'EMAIL', outcome: 'NOT_CONFIGURED' }],
    } as unknown as UserDetail);
    expect(cmp.registerOpen()).toBeFalse();
    expect(toast.success).toHaveBeenCalled();
    expect(toast.warning).toHaveBeenCalled();
    expect(portal.staff).toHaveBeenCalledTimes(3);

    // A failed registration that settles after a switch closes then.
    cmp.openRegister();
    cmp.onRegisterSubmitting(true);
    settingsSignal.set(settingsFixture({ facilityId: 'f-3' }));
    host();
    expect(cmp.registerOpen()).toBeTrue();
    cmp.onRegisterSubmitting(false);
    expect(cmp.registerOpen()).toBeFalse();
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
    expect(invalidate).toHaveBeenCalled();
  });

  // ── the facility goes away, or cannot be read ─────────────────────────

  it('a switch to a non-provider scope clears every page: no data, not available, no action', () => {
    const staff = render(ProviderStaffComponent);
    staff.cmp.openRegister();
    staff.cmp.askDeactivate(member());
    statusOverride.set('none');
    settingsSignal.set(null);
    const page = staff.host();
    expect(staff.cmp.staff()).toEqual([]);
    expect(staff.cmp.notAvailable()).toBeTrue();
    expect(staff.cmp.registerOpen()).toBeFalse();
    expect(staff.cmp.confirmDeactivate()).toBeNull();
    expect(page.querySelector('[data-test="deactivate"]')).toBeNull();
    expect(page.querySelector('[data-test="register"]')).toBeNull();
    TestBed.resetTestingModule();

    const profilePage = render(ProviderProfileComponent);
    profilePage.cmp.phoneNumber.set('+22670999999');
    statusOverride.set('none');
    settingsSignal.set(null);
    profilePage.host();
    expect(profilePage.cmp.profile()).toBeNull();
    expect(profilePage.cmp.phoneNumber()).toBe('');
    expect(profilePage.cmp.canSave()).toBeFalse();
    profilePage.cmp.save();
    expect(portal.updateProfile).not.toHaveBeenCalled();
    TestBed.resetTestingModule();

    const audit = render(ProviderAuditComponent);
    statusOverride.set('none');
    audit.host();
    expect(audit.cmp.page()).toBeNull();
    expect(audit.cmp.notAvailable()).toBeTrue();
    TestBed.resetTestingModule();

    const home = render(ProviderHomeComponent);
    statusOverride.set('none');
    home.host();
    expect(home.cmp.profile()).toBeNull();
    expect(home.cmp.notAvailable()).toBeTrue();
  });

  it('admin-only controls appear only on a successful answer that says admin', () => {
    const { host } = render(ProviderStaffComponent);
    expect(host().querySelector('[data-test="register"]')).not.toBeNull();

    // A settings error: the guard let the page open in its retry state
    // without knowing, so nothing admin-only shows, stale settings or not.
    statusOverride.set('error');
    expect(host().querySelector('[data-test="register"]')).toBeNull();

    // A plain staff member's answer: no Register either.
    statusOverride.set(null);
    settingsSignal.set(settingsFixture({ providerAdmin: false }));
    expect(host().querySelector('[data-test="register"]')).toBeNull();
  });

  it('a context that cannot be read shows the retry state; retry asks the context again', () => {
    const { cmp, host } = render(ProviderStaffComponent);
    statusOverride.set('error');
    settingsSignal.set(null);
    host();
    expect(cmp.loadFailed()).toBeTrue();
    expect(cmp.staff()).toEqual([]);

    cmp.retry();
    expect(invalidate).toHaveBeenCalledTimes(1);

    // Back to the facility: read again.
    statusOverride.set(null);
    settingsSignal.set(settingsFixture());
    host();
    expect(portal.staff).toHaveBeenCalledTimes(2);
    expect(cmp.loadFailed()).toBeFalse();

    // A page-level failure with the facility still shown retries the page read.
    portal.staff.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    cmp.load();
    cmp.retry();
    expect(portal.staff).toHaveBeenCalledTimes(4);
    expect(invalidate).toHaveBeenCalledTimes(1);
  });

  it('home, profile and audit retry the same way', () => {
    for (const component of [
      ProviderHomeComponent,
      ProviderProfileComponent,
      ProviderAuditComponent,
    ]) {
      const { cmp, host } = render<{ retry: () => void; loadFailed: () => boolean }>(
        component as Type<{ retry: () => void; loadFailed: () => boolean }>,
      );
      statusOverride.set('error');
      host();
      expect(cmp.loadFailed()).toBeTrue();
      cmp.retry();
      expect(invalidate).toHaveBeenCalled();
      invalidate.calls.reset();
      TestBed.resetTestingModule();
    }
  });

  it('audit: an empty page after the first keeps Previous', () => {
    const { cmp, host } = render(ProviderAuditComponent);
    portal.audit.and.returnValue(
      of({ entries: [], page: 1, size: 20, totalElements: 20, hasMore: false }),
    );
    cmp.nextPage();
    const page = host();
    expect(page.querySelector('[data-test="empty"]')).not.toBeNull();
    const pager = page.querySelector('[data-test="pager"]');
    expect(pager).not.toBeNull();
    const previous = pager!.querySelector('button') as HTMLButtonElement;
    expect(previous.disabled).toBeFalse();
    cmp.previousPage();
    expect(portal.audit).toHaveBeenCalledWith(0, 20);
  });

  // ── audit ─────────────────────────────────────────────────────────────

  it('audit: lists the trail and pages while the server has more', () => {
    const { cmp, host } = render(ProviderAuditComponent);
    expect(host().querySelectorAll('[data-test="audit-row"]').length).toBe(2);
    // Platform support acting as a member: the row says so, by whom.
    const bySupport = host().querySelectorAll('[data-test="by-support"]');
    expect(bySupport.length).toBe(1);
    // A role-assignment row names the role it assigned as its subject.
    const records = [...host().querySelectorAll('[data-test="audit-record"]')].map((c) =>
      c.textContent?.trim(),
    );
    expect(records[1]).toBe('PROVIDER.AUDIT.RECORD_ROLE_ASSIGNED');
    expect(cmp.recordKey('PROVIDER_FACILITY')).toBe('PROVIDER.AUDIT.RECORD_FACILITY');
    expect(cmp.recordKey('PROVIDER_STAFF')).toBe('PROVIDER.AUDIT.RECORD_STAFF');
    expect(cmp.recordKey('USER')).toBe('PROVIDER.AUDIT.RECORD_OTHER');
    expect(cmp.recordKey('USER_ROLE_ASSIGNMENT')).toBe('PROVIDER.AUDIT.RECORD_ASSIGNMENT');
    expect(cmp.recordKey(null)).toBe('PROVIDER.AUDIT.RECORD_OTHER');

    cmp.previousPage();
    expect(portal.audit).toHaveBeenCalledTimes(1);
    cmp.nextPage();
    expect(portal.audit).toHaveBeenCalledWith(1, 20);
    cmp.previousPage();
    expect(portal.audit).toHaveBeenCalledWith(0, 20);
  });

  it('audit: a branch switch starts the trail again at its first page; the old answer is dropped', () => {
    const { cmp, host } = render(ProviderAuditComponent);
    const lateA = new Subject<ProviderAuditPage>();
    portal.audit.and.returnValue(lateA);
    cmp.nextPage();
    expect(cmp.pageIndex()).toBe(1);
    portal.audit.and.returnValue(
      of({ entries: [], page: 0, size: 20, totalElements: 0, hasMore: false }),
    );

    settingsSignal.set(settingsFixture({ facilityId: 'f-2' }));
    host();
    lateA.next({ entries: [], page: 1, size: 20, totalElements: 99, hasMore: true });

    expect(cmp.pageIndex()).toBe(0);
    expect(portal.audit).toHaveBeenCalledWith(0, 20);
    expect(cmp.page()?.totalElements).toBe(0);
  });

  it('audit: a failure for the branch the page has left is dropped', () => {
    const lateA = new Subject<ProviderAuditPage>();
    portal.audit.and.returnValue(lateA);
    const { cmp, host } = render(ProviderAuditComponent);
    portal.audit.and.returnValue(
      of({ entries: [], page: 0, size: 20, totalElements: 0, hasMore: false }),
    );
    settingsSignal.set(settingsFixture({ facilityId: 'f-2' }));
    host();
    lateA.error(NOT_AVAILABLE);
    expect(cmp.notAvailable()).toBeFalse();
    expect(invalidate).not.toHaveBeenCalled();
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
    expect(invalidate).toHaveBeenCalled();
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
    const submitting: boolean[] = [];
    cmp.registered.subscribe((u) => (registered = u));
    cmp.submitting.subscribe((busy) => submitting.push(busy));
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
    // The parent is told while it is in flight, and when it settles.
    expect(submitting).toEqual([true, false]);
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
