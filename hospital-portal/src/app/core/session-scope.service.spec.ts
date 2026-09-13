import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { AuthService, LoginUserProfile, SessionBootstrapResponse } from '../auth/auth.service';
import { RoleContextService } from './role-context.service';
import { SessionScopeService } from './session-scope.service';

/**
 * E9 #55b — the hospital scope is hydrated from the live session, never from
 * the token, and a non-admin's permitted set is applied exactly as the server
 * states it (the portal used to collapse it to one hospital).
 */
describe('SessionScopeService', () => {
  const h1 = '11111111-1111-1111-1111-111111111111';
  const h2 = '22222222-2222-2222-2222-222222222222';
  const h3 = '33333333-3333-3333-3333-333333333333';

  let auth: jasmine.SpyObj<AuthService>;
  let roleContext: RoleContextService;
  let service: SessionScopeService;
  let stored: LoginUserProfile | null;

  function bootstrap(overrides: Partial<SessionBootstrapResponse> = {}): SessionBootstrapResponse {
    return {
      userId: 'u-1',
      username: 'nurse.one',
      email: 'nurse@example.test',
      authSource: 'internal',
      roles: ['ROLE_NURSE'],
      superAdmin: false,
      hospitalAdmin: false,
      primaryHospitalId: h1,
      primaryHospitalName: 'CHU Yalgado',
      permittedHospitalIds: [h1, h2, h3],
      staffId: 's-1',
      staffRoleCode: 'ROLE_NURSE',
      ...overrides,
    };
  }

  beforeEach(() => {
    stored = null;
    auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'sessionBootstrap',
      'getRoles',
      'getUserProfile',
      'setUserProfile',
    ]);
    auth.getRoles.and.returnValue(['ROLE_NURSE']);
    auth.getUserProfile.and.callFake(() => stored);
    auth.setUserProfile.and.callFake((p: LoginUserProfile) => {
      stored = p;
    });
    TestBed.configureTestingModule({
      providers: [
        RoleContextService,
        SessionScopeService,
        { provide: AuthService, useValue: auth },
      ],
    });
    roleContext = TestBed.inject(RoleContextService);
    service = TestBed.inject(SessionScopeService);
  });

  it('applies the permitted set exactly as the server states it — a nurse with three hospitals keeps three', () => {
    auth.sessionBootstrap.and.returnValue(of(bootstrap()));

    let emitted: SessionBootstrapResponse | null | undefined;
    service.hydrate().subscribe((b) => (emitted = b));

    expect(emitted?.userId).toBe('u-1');
    expect(roleContext.permittedHospitalIds).toEqual([h1, h2, h3]);
    expect(roleContext.activeHospitalId).toBe(h1);
    expect(roleContext.activeRoles).toEqual(['ROLE_NURSE']);
    expect(stored?.hospitalIds).toEqual([h1, h2, h3]);
    expect(stored?.primaryHospitalName).toBe('CHU Yalgado');
    expect(stored?.profileType).toBe('STAFF');
  });

  it('locks a single-hospital user to that hospital', () => {
    service.applyBootstrap(bootstrap({ permittedHospitalIds: [h2], primaryHospitalId: h1 }));

    expect(roleContext.activeHospitalId).toBe(h2);
  });

  it('keeps the login-only fields on the stored profile across a re-hydration', () => {
    service.applyBootstrap(bootstrap(), {
      forcePasswordChange: true,
      phoneNumber: '+22670000000',
      licenseNumber: 'BF-123',
    });
    service.applyBootstrap(bootstrap());

    expect(stored?.forcePasswordChange).toBeTrue();
    expect(stored?.phoneNumber).toBe('+22670000000');
    expect(stored?.licenseNumber).toBe('BF-123');
  });

  it('marks the super-admin global default from the live roles, not the token', () => {
    auth.getRoles.and.returnValue(['ROLE_DOCTOR']);
    service.applyBootstrap(bootstrap({ roles: ['ROLE_SUPER_ADMIN'], superAdmin: true }));

    expect(roleContext.activeRoles).toEqual(['ROLE_SUPER_ADMIN']);
    expect(roleContext.globalView()).toBeTrue();
  });

  it('falls back to the stored profile when the server cannot be asked, and emits null', () => {
    stored = {
      id: 'u-1',
      username: 'nurse.one',
      email: '',
      roles: ['ROLE_NURSE'],
      active: true,
      primaryHospitalId: h2,
      hospitalIds: [h2, h3],
    };
    auth.sessionBootstrap.and.returnValue(throwError(() => new Error('offline')));

    let emitted: SessionBootstrapResponse | null | undefined = undefined;
    service.hydrate().subscribe((b) => (emitted = b));

    expect(emitted).toBeNull();
    expect(roleContext.permittedHospitalIds).toEqual([h2, h3]);
    expect(roleContext.activeHospitalId).toBe(h2);
  });

  it('keeps the current active hospital when it is still permitted and no primary is stated', () => {
    roleContext.activeHospitalId = h3;
    service.applyScope([h2, h3], null);

    expect(roleContext.activeHospitalId).toBe(h3);
  });

  it('drops an active hospital the session no longer permits', () => {
    roleContext.activeHospitalId = h1;
    service.applyScope([h2, h3], null);

    expect(roleContext.activeHospitalId).toBe(h2);
  });

  it('clears the scope when the session permits nothing', () => {
    roleContext.activeHospitalId = h1;
    service.applyScope([], null);

    expect(roleContext.permittedHospitalIds).toEqual([]);
    expect(roleContext.activeHospitalId).toBeNull();
  });
});
