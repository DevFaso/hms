import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { Login } from './login';
import { OidcAuthService } from '../auth/oidc-auth.service';
import { AuthService, SessionBootstrapResponse } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { environment } from '../../environments/environment';

describe('Login — KC-2b SSO entry point', () => {
  let component: Login;
  let oidcSpy: jasmine.SpyObj<OidcAuthService>;
  const originalOidcEnabled = environment.oidc.enabled;

  beforeEach(() => {
    oidcSpy = jasmine.createSpyObj<OidcAuthService>('OidcAuthService', [
      'isEnabled',
      'isAvailable',
      'discoveryFailed',
      'login',
    ]);
    oidcSpy.isEnabled.and.callFake(() => environment.oidc.enabled);
    // KC-2b (G-8): the SSO button now binds to isAvailable() — i.e.
    // enabled AND discovery succeeded. In these tests there's no
    // discovery hop, so default to mirroring the env flag.
    oidcSpy.isAvailable.and.callFake(() => environment.oidc.enabled);
    oidcSpy.discoveryFailed.and.returnValue(false);

    TestBed.configureTestingModule({
      imports: [Login, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: OidcAuthService, useValue: oidcSpy },
      ],
    });
    const fixture = TestBed.createComponent(Login);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    environment.oidc.enabled = originalOidcEnabled;
  });

  it('oidcLoginEnabled mirrors the environment flag via OidcAuthService', () => {
    environment.oidc.enabled = false;
    expect(component.oidcLoginEnabled).toBeFalse();

    environment.oidc.enabled = true;
    expect(component.oidcLoginEnabled).toBeTrue();
  });

  it('loginWithKeycloak() delegates to OidcAuthService.login() only when enabled', () => {
    environment.oidc.enabled = false;
    component.loginWithKeycloak();
    expect(oidcSpy.login).not.toHaveBeenCalled();

    environment.oidc.enabled = true;
    component.loginWithKeycloak();
    expect(oidcSpy.login).toHaveBeenCalledTimes(1);
  });

  it('loginWithKeycloak() clears any prior error banner before redirecting', () => {
    environment.oidc.enabled = true;
    component.error = 'previous failure';
    component.loginWithKeycloak();
    expect(component.error).toBe('');
  });
});

/**
 * Password-login regression test (Copilot review on commit 2871c0a7).
 *
 * The login flow used to skip {@code markSuperAdminGlobalDefaults()}
 * so the auth interceptor's {@code effectiveHospitalIdForRequest}
 * fell back to {@code _activeHospitalId} (the JWT primary) on every
 * request — silently scoping the super-admin to their primary
 * hospital despite the chip dropdown showing "All hospitals".
 *
 * This describe block pins the contract: after a successful
 * password-login for a SUPER_ADMIN, RoleContextService MUST be in
 * global view ({@code globalView()=true},
 * {@code effectiveHospitalIdForRequest()=null}) BEFORE the router
 * navigates away. Without this assertion the scoping bug can
 * regress unnoticed.
 */
describe('Login — password flow leaves SUPER_ADMIN in global view', () => {
  let httpMock: HttpTestingController;
  let roleContext: RoleContextService;
  let component: Login;
  let oidcSpy: jasmine.SpyObj<OidcAuthService>;
  let authSpy: jasmine.SpyObj<AuthService>;

  const primaryHospitalId = '82430285-ef9e-4a2f-88d1-f4963c73cbe5';
  const otherHospitalId = '9ee57cac-193c-41e6-86c6-a8db507b0d68';

  beforeEach(() => {
    oidcSpy = jasmine.createSpyObj<OidcAuthService>('OidcAuthService', [
      'isEnabled',
      'isAvailable',
      'discoveryFailed',
      'login',
    ]);
    oidcSpy.isEnabled.and.returnValue(false);
    oidcSpy.isAvailable.and.returnValue(false);
    oidcSpy.discoveryFailed.and.returnValue(false);

    // Mock AuthService so we can drive what getRoles() returns
    // post-setToken and what sessionBootstrap() resolves to.
    authSpy = jasmine.createSpyObj<AuthService>('AuthService', [
      'setToken',
      'setRefreshToken',
      'getRoles',
      'sessionBootstrap',
      'setUserProfile',
      'getUserProfile',
      'getPermittedHospitalIds',
    ]);
    authSpy.getRoles.and.returnValue(['ROLE_SUPER_ADMIN']);
    authSpy.getPermittedHospitalIds.and.returnValue([primaryHospitalId, otherHospitalId]);
    authSpy.getUserProfile.and.returnValue(null);

    TestBed.configureTestingModule({
      imports: [Login, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: OidcAuthService, useValue: oidcSpy },
        { provide: AuthService, useValue: authSpy },
      ],
    });
    const fixture = TestBed.createComponent(Login);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    roleContext = TestBed.inject(RoleContextService);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('seeds globalView=true and clears effective hospital scope after JWT-only login', () => {
    // Sanity: a brand-new RoleContextService starts in non-global view
    // so the assertion below is meaningful.
    expect(roleContext.globalView()).toBeFalse();

    // Drive the password-login form
    component.username = 'tchico1er';
    component.password = 'irrelevant';

    // bootstrap-status check fires in ngOnInit. Match-and-flush whatever
    // URL pattern the component uses, then move on.
    httpMock
      .expectOne((req) => req.url.includes('/auth/bootstrap-status'))
      .flush({
        allowed: false,
      });

    component.submit();

    // /auth/login returns a super-admin token
    const loginReq = httpMock.expectOne((req) => req.url.endsWith('/auth/login'));
    expect(loginReq.request.method).toBe('POST');
    loginReq.flush({
      token: 'fake-jwt-super-admin',
      refreshToken: 'fake-refresh',
      id: 'user-1',
      username: 'tchico1er',
      primaryHospitalId,
      hospitalIds: [primaryHospitalId, otherHospitalId],
    });

    // sessionBootstrap() is called via AuthService; stub it to return a
    // super-admin context with the user's primary hospital. The login
    // path THEN calls setRoles + setPermittedHospitalIds + activeHospitalId,
    // and finally markSuperAdminGlobalDefaults — which is what we
    // assert below.
    const bootstrap: SessionBootstrapResponse = {
      userId: 'user-1',
      username: 'tchico1er',
      email: 'tchico1er@example.com',
      firstName: 'Tiego',
      lastName: 'Ouedraogo',
      authSource: 'internal',
      roles: ['ROLE_SUPER_ADMIN'],
      superAdmin: true,
      hospitalAdmin: false,
      primaryHospitalId,
      primaryHospitalName: 'Hospital D',
      permittedHospitalIds: [primaryHospitalId, otherHospitalId],
    };
    authSpy.sessionBootstrap.and.returnValue(of(bootstrap));

    // The login subscribe handler fires synchronously after flush.
    // It then calls markSuperAdminGlobalDefaults() before navigating.
    expect(roleContext.globalView())
      .withContext('post-login: SUPER_ADMIN MUST land in global view')
      .toBeTrue();

    // The auth interceptor reads effectiveHospitalIdForRequest()
    // before every HTTP call. For SUPER_ADMIN in global view the
    // return MUST be null so no X-Hospital-Id header is sent —
    // otherwise the backend scopes the list to the primary hospital
    // (the exact bug this regression test guards against).
    expect(roleContext.effectiveHospitalIdForRequest())
      .withContext('post-login: SUPER_ADMIN interceptor scope MUST be null')
      .toBeNull();
  });

  it('still seeds globalView=true when sessionBootstrap returns roles the JWT did not carry', () => {
    // Edge case: JWT issued without SUPER_ADMIN, but the DB-authoritative
    // sessionBootstrap response upgrades the user to SUPER_ADMIN. The
    // login flow MUST re-seed global view after bootstrap-setRoles,
    // otherwise a freshly-promoted super-admin would still be chip-
    // scoped to their primary hospital on first login.
    authSpy.getRoles.and.returnValue(['ROLE_HOSPITAL_ADMIN']); // JWT only

    component.username = 'tchico1er';
    component.password = 'irrelevant';

    httpMock
      .expectOne((req) => req.url.includes('/auth/bootstrap-status'))
      .flush({
        allowed: false,
      });

    component.submit();

    const loginReq = httpMock.expectOne((req) => req.url.endsWith('/auth/login'));
    loginReq.flush({
      token: 'fake-jwt-hospital-admin',
      refreshToken: 'fake-refresh',
      id: 'user-1',
      username: 'tchico1er',
      primaryHospitalId,
      hospitalIds: [primaryHospitalId],
    });

    // After JWT-seed: not super-admin yet, so the first
    // markSuperAdminGlobalDefaults() is a no-op.
    expect(roleContext.globalView()).toBeFalse();

    // Bootstrap upgrades the user to SUPER_ADMIN. The login flow's
    // SECOND markSuperAdminGlobalDefaults() call (after bootstrap
    // setRoles) is what makes this work.
    const upgradedBootstrap: SessionBootstrapResponse = {
      userId: 'user-1',
      username: 'tchico1er',
      email: 'tchico1er@example.com',
      authSource: 'internal',
      roles: ['ROLE_SUPER_ADMIN'], // upgraded from HOSPITAL_ADMIN in JWT
      superAdmin: true,
      hospitalAdmin: false,
      primaryHospitalId,
      permittedHospitalIds: [primaryHospitalId, otherHospitalId],
    };
    authSpy.sessionBootstrap.and.returnValue(of(upgradedBootstrap));

    expect(roleContext.globalView())
      .withContext('post-bootstrap: bootstrap-upgraded SUPER_ADMIN MUST land in global view')
      .toBeTrue();
    expect(roleContext.effectiveHospitalIdForRequest())
      .withContext('post-bootstrap: interceptor scope MUST be null for upgraded super-admin')
      .toBeNull();
  });

  it('does NOT flip globalView for non-super-admin logins', () => {
    // Negative case: a HOSPITAL_ADMIN logs in with a primary hospital.
    // markSuperAdminGlobalDefaults is a no-op for them, so the
    // interceptor correctly sends X-Hospital-Id = their primary
    // (existing single-tenant behaviour the regression must preserve).
    authSpy.getRoles.and.returnValue(['ROLE_HOSPITAL_ADMIN']);

    component.username = 'hospital_admin';
    component.password = 'irrelevant';

    httpMock
      .expectOne((req) => req.url.includes('/auth/bootstrap-status'))
      .flush({
        allowed: false,
      });

    component.submit();

    const loginReq = httpMock.expectOne((req) => req.url.endsWith('/auth/login'));
    loginReq.flush({
      token: 'fake-jwt-hospital-admin',
      refreshToken: 'fake-refresh',
      id: 'user-2',
      username: 'hospital_admin',
      primaryHospitalId,
      hospitalIds: [primaryHospitalId],
    });

    const hospitalAdminBootstrap: SessionBootstrapResponse = {
      userId: 'user-2',
      username: 'hospital_admin',
      email: 'hospital_admin@example.com',
      authSource: 'internal',
      roles: ['ROLE_HOSPITAL_ADMIN'],
      superAdmin: false,
      hospitalAdmin: true,
      primaryHospitalId,
      permittedHospitalIds: [primaryHospitalId],
    };
    authSpy.sessionBootstrap.and.returnValue(of(hospitalAdminBootstrap));

    expect(roleContext.globalView())
      .withContext('hospital-admin login MUST NOT flip globalView')
      .toBeFalse();
    expect(roleContext.effectiveHospitalIdForRequest())
      .withContext('hospital-admin interceptor scope MUST be the primary hospital')
      .toBe(primaryHospitalId);
  });
});
