import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { OAuthService } from 'angular-oauth2-oidc';

import { AuthService } from './auth.service';
import { OidcAuthService } from './oidc-auth.service';
import { environment } from '../../environments/environment';
import { SSO_TAB_KEY, TAB_SESSION_KEY, tabBindingFor } from '../core/browser-storage';

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
        provideHttpClient(withXhr()),
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

  describe('a reload of a tab signed in by SSO (its tokens were in memory only)', () => {
    const claims = {
      sub: 'kc-user-1',
      preferred_username: 'dev.doctor',
      realm_access: { roles: ['DOCTOR'] },
    };
    const kcToken = (sub: string) => {
      const b64 = (v: string) =>
        btoa(v).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
      return `${b64('{"alg":"none"}')}.${b64(JSON.stringify({ sub, exp: 9999999999 }))}.sig`;
    };

    beforeEach(async () => {
      environment.oidc.enabled = true;
      sessionStorage.setItem(TAB_SESSION_KEY, await tabBindingFor('kc-user-1'));
    });

    it('an SSO sign-in marks the tab, so a reload never asks the native refresh cookie', async () => {
      sessionStorage.removeItem(TAB_SESSION_KEY);
      oauthMock.hasValidAccessToken.and.returnValue(true);
      oauthMock.getAccessToken.and.returnValue(kcToken('kc-user-1'));
      oauthMock.getIdentityClaims.and.returnValue(claims);

      await service.initialize();

      expect(sessionStorage.getItem(SSO_TAB_KEY)).toBe('bound');
    });

    it('goes back to the identity provider once to get the session again', async () => {
      sessionStorage.setItem(SSO_TAB_KEY, 'bound');

      void service.initialize();
      await new Promise((resolve) => setTimeout(resolve, 0));

      expect(oauthMock.initCodeFlow).toHaveBeenCalledTimes(1);
      expect(sessionStorage.getItem(SSO_TAB_KEY)).toBe('restoring');
      expect(auth.hasTabSession()).toBeTrue();
    });

    it('back from the provider with the same account, the tab is restored and bound again', async () => {
      sessionStorage.setItem(SSO_TAB_KEY, 'restoring');
      oauthMock.hasValidAccessToken.and.returnValue(true);
      oauthMock.getAccessToken.and.returnValue(kcToken('kc-user-1'));
      oauthMock.getIdentityClaims.and.returnValue(claims);

      await service.initialize();

      expect(auth.getToken()).toBe(kcToken('kc-user-1'));
      expect(sessionStorage.getItem(SSO_TAB_KEY)).toBe('bound');
      expect(service.authenticated()).toBeTrue();
      expect(oauthMock.initCodeFlow).not.toHaveBeenCalled();
    });

    it("r81: the lock screen's sign-in asks the provider to authenticate the holder again and marks the tab", () => {
      spyOn(service, 'isEnabled').and.returnValue(true);
      sessionStorage.setItem(SSO_TAB_KEY, 'bound');

      service.reauthenticate();

      expect(oauthMock.initCodeFlow).toHaveBeenCalledOnceWith('', { prompt: 'login' });
      expect(sessionStorage.getItem(SSO_TAB_KEY)).toBe('unlocking');
    });

    it('r81: back from that sign-in the tab says so once; back from a plain reload it does not', async () => {
      oauthMock.hasValidAccessToken.and.returnValue(true);
      oauthMock.getAccessToken.and.returnValue(kcToken('kc-user-1'));
      oauthMock.getIdentityClaims.and.returnValue(claims);

      sessionStorage.setItem(SSO_TAB_KEY, 'restoring');
      await service.initialize();
      expect(service.reauthenticated()).toBeFalse();

      sessionStorage.setItem(SSO_TAB_KEY, 'unlocking');
      await service.initialize();
      expect(service.reauthenticated()).toBeTrue();
      expect(sessionStorage.getItem(SSO_TAB_KEY)).toBe('bound');
    });

    it('r81: back from that sign-in as another account, the tab is ended and nothing is unlocked', async () => {
      sessionStorage.setItem(SSO_TAB_KEY, 'unlocking');
      oauthMock.hasValidAccessToken.and.returnValue(true);
      oauthMock.getAccessToken.and.returnValue(kcToken('kc-someone-else'));
      oauthMock.getIdentityClaims.and.returnValue({ ...claims, sub: 'kc-someone-else' });

      await service.initialize();

      expect(service.reauthenticated()).toBeFalse();
      expect(auth.getToken()).toBeNull();
      expect(sessionStorage.getItem(SSO_TAB_KEY)).toBeNull();
    });

    it('back from the provider as another account, the tab is ended locally', async () => {
      sessionStorage.setItem(SSO_TAB_KEY, 'restoring');
      oauthMock.hasValidAccessToken.and.returnValue(true);
      oauthMock.getAccessToken.and.returnValue(kcToken('kc-someone-else'));
      oauthMock.getIdentityClaims.and.returnValue({ ...claims, sub: 'kc-someone-else' });

      await service.initialize();

      expect(auth.getToken()).toBeNull();
      expect(auth.hasTabSession()).toBeFalse();
      expect(sessionStorage.getItem(SSO_TAB_KEY)).toBeNull();
    });

    it('back from the provider without a token, the tab signs in again (no second round trip)', async () => {
      sessionStorage.setItem(SSO_TAB_KEY, 'restoring');

      await service.initialize();

      expect(oauthMock.initCodeFlow).not.toHaveBeenCalled();
      expect(auth.hasTabSession()).toBeFalse();
      expect(sessionStorage.getItem(SSO_TAB_KEY)).toBeNull();
    });

    it('the provider unreachable on a reload, the tab signs in again', async () => {
      sessionStorage.setItem(SSO_TAB_KEY, 'bound');
      oauthMock.loadDiscoveryDocumentAndTryLogin.and.rejectWith(new Error('issuer unreachable'));

      await service.initialize();

      expect(oauthMock.initCodeFlow).not.toHaveBeenCalled();
      expect(auth.hasTabSession()).toBeFalse();
    });
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

  it('retrying initialize after a discovery failure clears discoveryFailed when discovery succeeds', async () => {
    environment.oidc.enabled = true;

    // First attempt: issuer unreachable.
    oauthMock.loadDiscoveryDocumentAndTryLogin.and.rejectWith(new Error('issuer unreachable'));
    await service.initialize();
    expect(service.discoveryFailed())
      .withContext('first attempt should flag discovery failure')
      .toBeTrue();
    expect(service.isAvailable()).toBeFalse();

    // Second attempt: issuer recovered. The flag must reset so the
    // SSO button comes back without a hard refresh.
    oauthMock.loadDiscoveryDocumentAndTryLogin.and.resolveTo(true);
    await service.initialize();
    expect(service.discoveryFailed())
      .withContext('successful retry must clear the flag')
      .toBeFalse();
    expect(service.isAvailable()).toBeTrue();
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
