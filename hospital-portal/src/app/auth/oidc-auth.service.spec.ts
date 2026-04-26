import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { OAuthService } from 'angular-oauth2-oidc';

import { AuthService } from './auth.service';
import { OidcAuthService } from './oidc-auth.service';
import { environment } from '../../environments/environment';

describe('OidcAuthService — KC-2b PKCE driver', () => {
  let oauthMock: jasmine.SpyObj<OAuthService>;
  let auth: AuthService;
  let service: OidcAuthService;

  const originalOidc = { ...environment.oidc };

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();

    oauthMock = jasmine.createSpyObj<OAuthService>('OAuthService', [
      'configure',
      'loadDiscoveryDocumentAndTryLogin',
      'hasValidAccessToken',
      'hasValidIdToken',
      'getAccessToken',
      'getIdentityClaims',
      'initCodeFlow',
      'logOut',
      'setupAutomaticSilentRefresh',
    ]);
    oauthMock.loadDiscoveryDocumentAndTryLogin.and.resolveTo(true);
    oauthMock.hasValidAccessToken.and.returnValue(false);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: OAuthService, useValue: oauthMock },
      ],
    });
    auth = TestBed.inject(AuthService);
    service = TestBed.inject(OidcAuthService);
  });

  afterEach(() => {
    Object.assign(environment.oidc, originalOidc);
    localStorage.clear();
    sessionStorage.clear();
  });

  it('initialize is a no-op when oidc is disabled', async () => {
    environment.oidc.enabled = false;
    await service.initialize();
    expect(oauthMock.configure).not.toHaveBeenCalled();
    expect(oauthMock.loadDiscoveryDocumentAndTryLogin).not.toHaveBeenCalled();
    expect(service.ready()).toBeTrue();
    expect(service.authenticated()).toBeFalse();
  });

  it('initialize hydrates AuthService from OIDC claims when login succeeds', async () => {
    environment.oidc.enabled = true;
    oauthMock.hasValidAccessToken.and.returnValue(true);
    oauthMock.getAccessToken.and.returnValue('kc-access-token');
    oauthMock.getIdentityClaims.and.returnValue({
      sub: 'user-uuid-1',
      preferred_username: 'dev.doctor',
      email: 'dev.doctor@hms.local',
      given_name: 'Dev',
      family_name: 'Doctor',
      hospital_id: '11111111-1111-1111-1111-111111111111',
      realm_access: { roles: ['DOCTOR', 'STAFF', 'ROLE_PATIENT'] },
    });

    await service.initialize();

    expect(oauthMock.configure).toHaveBeenCalled();
    expect(oauthMock.loadDiscoveryDocumentAndTryLogin).toHaveBeenCalled();
    expect(auth.getToken()).toBe('kc-access-token');
    const profile = auth.getUserProfile();
    expect(profile).withContext('profile populated').toBeTruthy();
    expect(profile?.username).toBe('dev.doctor');
    expect(profile?.primaryHospitalId).toBe('11111111-1111-1111-1111-111111111111');
    // Already-prefixed roles must be left alone; bare names get the ROLE_ prefix.
    expect(profile?.roles).toEqual(['ROLE_DOCTOR', 'ROLE_STAFF', 'ROLE_PATIENT']);
    expect(oauthMock.setupAutomaticSilentRefresh).toHaveBeenCalled();
    expect(service.authenticated()).toBeTrue();
  });

  it('initialize tolerates discovery failure without throwing', async () => {
    environment.oidc.enabled = true;
    oauthMock.loadDiscoveryDocumentAndTryLogin.and.rejectWith(new Error('network down'));

    await expectAsync(service.initialize()).toBeResolved();
    expect(service.ready()).toBeTrue();
    expect(service.authenticated()).toBeFalse();
    expect(auth.getToken()).toBeNull();
  });

  it('initialize sets discoveryFailed and hides isAvailable when discovery rejects', async () => {
    environment.oidc.enabled = true;
    oauthMock.loadDiscoveryDocumentAndTryLogin.and.rejectWith(new Error('issuer unreachable'));

    expect(service.discoveryFailed()).toBeFalse();
    expect(service.isAvailable()).toBeTrue();

    await service.initialize();

    expect(service.discoveryFailed())
      .withContext('discoveryFailed flag must flip after a failed bootstrap')
      .toBeTrue();
    // isEnabled stays true (the flag is configured) but isAvailable hides
    // the SSO button so users fall back to the legacy form rather than
    // tripping over a 502 mid-flow.
    expect(service.isEnabled()).toBeTrue();
    expect(service.isAvailable()).toBeFalse();
  });

  it('isAvailable mirrors isEnabled when discovery has not yet run or has succeeded', async () => {
    environment.oidc.enabled = false;
    expect(service.isAvailable()).toBeFalse();

    environment.oidc.enabled = true;
    expect(service.isAvailable())
      .withContext('available before initialize() — discoveryFailed defaults to false')
      .toBeTrue();

    oauthMock.loadDiscoveryDocumentAndTryLogin.and.resolveTo(true);
    await service.initialize();
    expect(service.isAvailable()).toBeTrue();
    expect(service.discoveryFailed()).toBeFalse();
  });

  it('login() kicks off the PKCE code flow only when enabled', () => {
    environment.oidc.enabled = false;
    service.login();
    expect(oauthMock.initCodeFlow).not.toHaveBeenCalled();

    environment.oidc.enabled = true;
    service.login();
    expect(oauthMock.initCodeFlow).toHaveBeenCalledTimes(1);
  });

  it('logout() clears legacy AuthService state and calls Keycloak end-session when authenticated', () => {
    environment.oidc.enabled = true;
    auth.setToken('legacy-token');
    auth.setUserProfile({
      id: 'x',
      username: 'u',
      email: 'e',
      roles: [],
      active: true,
    });
    oauthMock.hasValidAccessToken.and.returnValue(true);

    service.logout();

    expect(auth.getToken()).toBeNull();
    expect(auth.getUserProfile()).toBeNull();
    expect(oauthMock.logOut).toHaveBeenCalled();
    expect(service.authenticated()).toBeFalse();
  });

  it('logout() skips Keycloak end-session when there is no active OIDC session', () => {
    environment.oidc.enabled = true;
    oauthMock.hasValidAccessToken.and.returnValue(false);
    oauthMock.hasValidIdToken.and.returnValue(false);

    service.logout();

    expect(oauthMock.logOut).not.toHaveBeenCalled();
  });
});
