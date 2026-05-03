import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ImpersonationService } from './impersonation.service';
import { AuthService, LoginUserProfile } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';

const ORIGINAL_TOKEN_KEY = 'auth_token_pre_impersonation';
const ORIGINAL_REMEMBER_KEY = 'auth_remember_pre_impersonation';
const ORIGINAL_PROFILE_KEY = 'auth_profile_pre_impersonation';

/**
 * Minimal RS256-shaped JWT: header.payload.signature, all base64url. The
 * payload is what {@link ImpersonationService} decodes to hydrate roles
 * and tenant claims, so only that segment matters.
 */
function fakeJwt(payload: Record<string, unknown>): string {
  const b64 = (s: string) => btoa(s).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
  return `${b64('{"alg":"none"}')}.${b64(JSON.stringify(payload))}.sig`;
}

describe('ImpersonationService', () => {
  let service: ImpersonationService;
  let http: HttpTestingController;
  let auth: jasmine.SpyObj<AuthService>;
  let roleContext: jasmine.SpyObj<RoleContextService>;
  let storedToken: string | null;
  let rememberFlag: boolean;
  let storedProfile: LoginUserProfile | null;

  beforeEach(() => {
    storedToken = 'super-admin.jwt';
    rememberFlag = true;
    storedProfile = {
      id: 'super-id',
      username: 'super.admin',
      email: 'super@example.com',
      roles: ['ROLE_SUPER_ADMIN'],
      active: true,
      hospitalIds: ['h1'],
      primaryHospitalId: 'h1',
    };
    auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'getToken',
      'setToken',
      'clearToken',
      'isTokenRemembered',
      'getUserProfile',
      'setUserProfile',
    ]);
    auth.getToken.and.callFake(() => storedToken);
    auth.setToken.and.callFake((token: string, remember = true) => {
      storedToken = token;
      rememberFlag = remember;
    });
    auth.clearToken.and.callFake(() => {
      storedToken = null;
    });
    auth.isTokenRemembered.and.callFake(() => rememberFlag);
    auth.getUserProfile.and.callFake(() => storedProfile);
    auth.setUserProfile.and.callFake((p: LoginUserProfile) => {
      storedProfile = p;
    });

    roleContext = jasmine.createSpyObj<RoleContextService>('RoleContextService', [
      'setRoles',
      'setPermittedHospitalIds',
    ]);
    // activeHospitalId is a setter — install it as a spied property
    Object.defineProperty(roleContext, 'activeHospitalId', {
      configurable: true,
      writable: true,
      value: null,
    });

    [ORIGINAL_TOKEN_KEY, ORIGINAL_REMEMBER_KEY, ORIGINAL_PROFILE_KEY].forEach((k) =>
      sessionStorage.removeItem(k),
    );

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: RoleContextService, useValue: roleContext },
      ],
    });
    service = TestBed.inject(ImpersonationService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    [ORIGINAL_TOKEN_KEY, ORIGINAL_REMEMBER_KEY, ORIGINAL_PROFILE_KEY].forEach((k) =>
      sessionStorage.removeItem(k),
    );
  });

  it('start() preserves original token + remember + profile, then swaps in impersonation token', () => {
    rememberFlag = true; // simulate a remember-me login
    const targetJwt = fakeJwt({
      sub: 'nurse.alice',
      roles: ['ROLE_NURSE'],
      hospitalIds: ['h2'],
      primaryHospitalId: 'h2',
    });
    service
      .start({ targetUserId: 'u1', reason: 'validating refill bug' }, '123456')
      .subscribe((response) => {
        expect(response.accessToken).toBe(targetJwt);
      });

    const req = http.expectOne('/super-admin/impersonation/start');
    expect(req.request.headers.get('X-Mfa-Token')).toBe('123456');
    req.flush({
      accessToken: targetJwt,
      expiresAt: '2026-05-02T20:00:00Z',
      impersonatorUserId: 'super-id',
      impersonatorUsername: 'super.admin',
      targetUserId: 'u1',
      targetUsername: 'nurse.alice',
    });

    // Original session preserved with the right `remember` flag (closes #5).
    expect(sessionStorage.getItem(ORIGINAL_TOKEN_KEY)).toBe('super-admin.jwt');
    expect(sessionStorage.getItem(ORIGINAL_REMEMBER_KEY)).toBe('1');
    expect(sessionStorage.getItem(ORIGINAL_PROFILE_KEY)).toContain('super.admin');

    // Impersonation token always installed in sessionStorage (remember=false)
    // and AuthService.setToken now clears the OTHER storage (closes #2).
    expect(auth.setToken).toHaveBeenCalledWith(targetJwt, false);

    // Role context hydrated from the impersonation JWT (closes #1, #3).
    expect(roleContext.setRoles).toHaveBeenCalledWith(['ROLE_NURSE']);
    expect(roleContext.setPermittedHospitalIds).toHaveBeenCalledWith(['h2']);

    // Profile snapshot reflects the target so the shell renders the right name.
    expect(storedProfile?.username).toBe('nurse.alice');
    expect(storedProfile?.roles).toEqual(['ROLE_NURSE']);

    expect(service.active()?.impersonating).toBeTrue();
    expect(service.isActive()).toBeTrue();
  });

  it('stop() restores original token, remember flag, profile, and role context', () => {
    sessionStorage.setItem(ORIGINAL_TOKEN_KEY, 'super-admin.jwt');
    sessionStorage.setItem(ORIGINAL_REMEMBER_KEY, '1');
    sessionStorage.setItem(
      ORIGINAL_PROFILE_KEY,
      JSON.stringify({
        id: 'super-id',
        username: 'super.admin',
        email: 'super@example.com',
        roles: ['ROLE_SUPER_ADMIN'],
        active: true,
        hospitalIds: ['h1'],
        primaryHospitalId: 'h1',
      }),
    );
    storedToken = 'imp.jwt';

    service.stop().subscribe();
    http.expectOne('/super-admin/impersonation/stop').flush({ impersonating: false });

    expect(auth.setToken).toHaveBeenCalledWith('super-admin.jwt', true);
    expect(roleContext.setRoles).toHaveBeenCalledWith(['ROLE_SUPER_ADMIN']);
    expect(roleContext.setPermittedHospitalIds).toHaveBeenCalledWith(['h1']);
    expect(storedProfile?.username).toBe('super.admin');

    // Snapshots cleared so a future impersonation re-snapshots cleanly.
    expect(sessionStorage.getItem(ORIGINAL_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(ORIGINAL_REMEMBER_KEY)).toBeNull();
    expect(sessionStorage.getItem(ORIGINAL_PROFILE_KEY)).toBeNull();

    expect(service.active()?.impersonating).toBeFalse();
    expect(service.isActive()).toBeFalse();
  });

  it('stop() preserves a session-only login by restoring with remember=false (closes #5)', () => {
    sessionStorage.setItem(ORIGINAL_TOKEN_KEY, 'super-admin.jwt');
    sessionStorage.setItem(ORIGINAL_REMEMBER_KEY, '0'); // session-only original
    storedToken = 'imp.jwt';

    service.stop().subscribe();
    http.expectOne('/super-admin/impersonation/stop').flush({ impersonating: false });

    expect(auth.setToken).toHaveBeenCalledWith('super-admin.jwt', false);
    expect(rememberFlag).toBeFalse();
  });

  it('forceStop() drops the impersonation token without hitting the server', () => {
    sessionStorage.setItem(ORIGINAL_TOKEN_KEY, 'super-admin.jwt');
    sessionStorage.setItem(ORIGINAL_REMEMBER_KEY, '1');

    service.forceStop();

    expect(auth.setToken).toHaveBeenCalledWith('super-admin.jwt', true);
    expect(service.active()?.impersonating).toBeFalse();
  });

  it('refreshActive() mirrors the server response into the signal', () => {
    service.refreshActive().subscribe();
    http.expectOne('/super-admin/impersonation/active').flush({
      impersonating: true,
      impersonatorUsername: 'super.admin',
      targetUsername: 'nurse.alice',
    });

    expect(service.active()?.impersonating).toBeTrue();
    expect(service.active()?.targetUsername).toBe('nurse.alice');
    expect(service.isActive()).toBeTrue();
  });
});
