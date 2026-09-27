import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';

import { apiPrefixInterceptor } from './auth.interceptor';
import { errorInterceptor, clearReportedSilent403s } from './error.interceptor';
import { AuthService, LoginUserProfile } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { SessionScopeService } from '../core/session-scope.service';
import { ImpersonationService } from '../services/impersonation.service';

/**
 * A hospital revoked after sign-in, end to end through the real interceptors,
 * RoleContextService and SessionScopeService: the NO_LONGER_PERMITTED 403 leads
 * to one bootstrap WITHOUT the stale X-Hospital-Id, and then to a valid scope,
 * never to a loop of refused bootstraps.
 */
describe('scope recovery after a revoked hospital', () => {
  const REVOKED = 'hospital-revoked';
  const KEPT = 'hospital-kept';

  let http: HttpClient;
  let httpMock: HttpTestingController;
  let roleContext: RoleContextService;
  let profile: LoginUserProfile | null;

  beforeEach(() => {
    clearReportedSilent403s();
    profile = {
      id: 'u1',
      username: 'dr',
      roles: ['ROLE_DOCTOR'],
      hospitalIds: [REVOKED, KEPT],
    } as LoginUserProfile;
    const auth = {
      getToken: () => 'token',
      isExpired: () => false,
      getRefreshToken: () => null,
      getRoles: () => ['ROLE_DOCTOR'],
      getUserProfile: () => profile,
      setUserProfile: (p: LoginUserProfile) => (profile = p),
      logout: jasmine.createSpy('logout'),
      sessionBootstrap: () => TestBed.inject(HttpClient).get('auth/session/bootstrap'),
    };
    const impersonation = jasmine.createSpyObj('ImpersonationService', ['isActive', 'forceStop']);
    impersonation.isActive.and.returnValue(false);
    const router = jasmine.createSpyObj('Router', ['navigate']);
    router.navigate.and.resolveTo(true);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr(), withInterceptors([apiPrefixInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
        { provide: ImpersonationService, useValue: impersonation },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    roleContext = TestBed.inject(RoleContextService);
    TestBed.inject(SessionScopeService);
    roleContext.setRoles(['ROLE_DOCTOR']);
    roleContext.setPermittedHospitalIds([REVOKED, KEPT]);
    roleContext.activeHospitalId = REVOKED;
  });

  afterEach(() => httpMock.verify());

  it('re-bootstraps once without the stale header, then scopes to a permitted hospital', () => {
    http.get('/patients').subscribe({ error: () => undefined });
    const refused = httpMock.expectOne((r) => r.url.endsWith('/patients'));
    expect(refused.request.headers.get('X-Hospital-Id')).toBe(REVOKED);
    refused.flush(
      { code: 'hospital_scope_refused', reason: 'NO_LONGER_PERMITTED' },
      { status: 403, statusText: 'Forbidden' },
    );

    const bootstrap = httpMock.expectOne((r) => r.url.endsWith('/auth/session/bootstrap'));
    expect(bootstrap.request.headers.has('X-Hospital-Id'))
      .withContext('the recovery call never carries the lost selection')
      .toBeFalse();
    expect(bootstrap.request.headers.get('Authorization')).toBe('Bearer token');
    bootstrap.flush({
      userId: 'u1',
      username: 'dr',
      roles: ['ROLE_DOCTOR'],
      permittedHospitalIds: [KEPT],
      primaryHospitalId: KEPT,
    });

    expect(roleContext.activeHospitalId).toBe(KEPT);
    expect(roleContext.permittedHospitalIds).toEqual([KEPT]);
    expect(profile?.hospitalIds).toEqual([KEPT]);

    http.get('/patients').subscribe();
    const next = httpMock.expectOne((r) => r.url.endsWith('/patients'));
    expect(next.request.headers.get('X-Hospital-Id')).toBe(KEPT);
    next.flush([]);
    httpMock.expectNone((r) => r.url.endsWith('/auth/session/bootstrap'));
  });

  it('a bootstrap that cannot be reached does not bring the stale hospital back from the stored profile', () => {
    http.get('/patients').subscribe({ error: () => undefined });
    httpMock
      .expectOne((r) => r.url.endsWith('/patients'))
      .flush(
        { code: 'hospital_scope_refused', reason: 'NO_LONGER_PERMITTED' },
        { status: 403, statusText: 'Forbidden' },
      );
    httpMock
      .expectOne((r) => r.url.endsWith('/auth/session/bootstrap'))
      .flush(null, { status: 503, statusText: 'Unavailable' });

    expect(roleContext.activeHospitalId).toBe(KEPT);
    http.get('/patients').subscribe();
    const next = httpMock.expectOne((r) => r.url.endsWith('/patients'));
    expect(next.request.headers.get('X-Hospital-Id')).not.toBe(REVOKED);
    next.flush([]);
  });
});
