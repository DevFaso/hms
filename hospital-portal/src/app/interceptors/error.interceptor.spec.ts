import { TestBed } from '@angular/core/testing';
import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
  withXhr,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { errorInterceptor, clearReportedSilent403s } from './error.interceptor';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { SessionScopeService } from '../core/session-scope.service';
import { ImpersonationService } from '../services/impersonation.service';
import { DowntimeService } from '../services/downtime.service';

describe('errorInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;
  let impersonation: jasmine.SpyObj<ImpersonationService>;
  let sessionScope: jasmine.SpyObj<SessionScopeService>;
  let selectedHospital: string | null;

  beforeEach(() => {
    clearReportedSilent403s();
    auth = jasmine.createSpyObj('AuthService', [
      'getRefreshToken',
      'getUserProfile',
      'logout',
      'refreshTokenRequest',
      'setToken',
      'setRefreshToken',
    ]);
    auth.getRefreshToken.and.returnValue(null);
    auth.getUserProfile.and.returnValue(null);
    router = jasmine.createSpyObj('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
    impersonation = jasmine.createSpyObj('ImpersonationService', ['isActive', 'forceStop']);
    impersonation.isActive.and.returnValue(false);
    sessionScope = jasmine.createSpyObj('SessionScopeService', ['hydrate', 'forgetHospital']);
    sessionScope.hydrate.and.returnValue(of(null));
    selectedHospital = 'hospital-a';

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr(), withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
        { provide: ImpersonationService, useValue: impersonation },
        { provide: SessionScopeService, useValue: sessionScope },
        {
          provide: RoleContextService,
          useValue: { effectiveHospitalIdForRequest: () => selectedHospital },
        },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('passes successful responses through untouched', () => {
    let body: unknown;
    http.get('/data').subscribe((b) => (body = b));
    httpMock.expectOne('/data').flush({ ok: true });
    expect(body).toEqual({ ok: true });
  });

  it('on 401 with a session, refreshes and retries with the new token', () => {
    auth.getRefreshToken.and.returnValue('r1');
    auth.refreshTokenRequest.and.returnValue(of({ accessToken: 'new-token', refreshToken: 'r2' }));

    let body: unknown;
    http.get('/data').subscribe((b) => (body = b));
    httpMock.expectOne('/data').flush(null, { status: 401, statusText: 'Unauthorized' });

    const retried = httpMock.expectOne('/data');
    expect(retried.request.headers.get('Authorization')).toBe('Bearer new-token');
    retried.flush({ ok: true });

    expect(auth.setToken).toHaveBeenCalledWith('new-token');
    expect(auth.setRefreshToken).toHaveBeenCalledWith('r2');
    expect(body).toEqual({ ok: true });
  });

  it('on 401 with a failing refresh, logs out and redirects to /login', () => {
    auth.getRefreshToken.and.returnValue('r1');
    auth.refreshTokenRequest.and.returnValue(throwError(() => new Error('refresh dead')));

    let error: unknown;
    http.get('/data').subscribe({ error: (e) => (error = e) });
    httpMock.expectOne('/data').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.logout).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
    expect(error).toBeTruthy();
  });

  it('on 401 with no session evidence, logs out without attempting refresh', () => {
    let error: HttpErrorResponse | undefined;
    http.get('/data').subscribe({ error: (e) => (error = e) });
    httpMock.expectOne('/data').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.refreshTokenRequest).not.toHaveBeenCalled();
    expect(auth.logout).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
    expect(error?.status).toBe(401);
  });

  it('never refreshes or logs out again on the 401 of the logout call itself', () => {
    // AuthService.logout() fires POST /auth/logout; an expired bearer answers
    // 401. A refresh here would mint a new session mid-logout, and a second
    // logout() would fire a second POST - the loop this guards.
    auth.getRefreshToken.and.returnValue('r1');
    auth.getUserProfile.and.returnValue({ id: 'u1' } as never);

    let error: HttpErrorResponse | undefined;
    http.post('/api/auth/logout', {}).subscribe({ error: (e) => (error = e) });
    httpMock.expectOne('/api/auth/logout').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.refreshTokenRequest).not.toHaveBeenCalled();
    expect(auth.logout).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
    expect(error?.status).toBe(401);
  });

  it('on 401 during impersonation, ends the session instead of refreshing', () => {
    impersonation.isActive.and.returnValue(true);
    auth.getRefreshToken.and.returnValue('r1');

    let error: HttpErrorResponse | undefined;
    http.get('/data').subscribe({ error: (e) => (error = e) });
    httpMock.expectOne('/data').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(impersonation.forceStop).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/super-admin']);
    expect(auth.refreshTokenRequest).not.toHaveBeenCalled();
    expect(error?.status).toBe(401);
  });

  it('on silent-pattern 403 GET, reports to the audit sink once and does not redirect', () => {
    let firstError: HttpErrorResponse | undefined;
    http.get('/hospitals').subscribe({ error: (e) => (firstError = e) });
    httpMock.expectOne('/hospitals').flush(null, { status: 403, statusText: 'Forbidden' });

    const audit = httpMock.expectOne('/frontend-audit');
    expect(audit.request.method).toBe('POST');
    expect((audit.request.body as { type: string }).type).toBe('SILENT_403');
    audit.flush({});

    expect(router.navigate).not.toHaveBeenCalled();
    expect(firstError?.status).toBe(403);

    // Same URL again → dedup: no second audit report.
    http.get('/hospitals').subscribe({ error: () => undefined });
    httpMock.expectOne('/hospitals').flush(null, { status: 403, statusText: 'Forbidden' });
    httpMock.expectNone('/frontend-audit');
  });

  it('on a 403 from a CDS Hooks invocation (a POST), degrades quietly and does not redirect', () => {
    let error: HttpErrorResponse | undefined;
    http.post('/cds-services/hms-bpa-protocols', {}).subscribe({ error: (e) => (error = e) });
    httpMock
      .expectOne('/cds-services/hms-bpa-protocols')
      .flush(null, { status: 403, statusText: 'Forbidden' });
    httpMock.expectOne('/frontend-audit').flush({});

    expect(router.navigate).not.toHaveBeenCalled();
    expect(error?.status).toBe(403);
  });

  describe('hospital scope refused (the one tenant resolver)', () => {
    function refuse(reason: string, hospitalId?: string): HttpErrorResponse | undefined {
      let error: HttpErrorResponse | undefined;
      http.get('/patients').subscribe({ error: (e) => (error = e) });
      httpMock
        .expectOne('/patients')
        .flush(
          { code: 'hospital_scope_refused', reason, ...(hospitalId ? { hospitalId } : {}) },
          { status: 403, statusText: 'Forbidden' },
        );
      return error;
    }

    it('a stale chip (the SELECTED hospital refused): forgets it and re-reads the scope, no forbidden page', () => {
      const error = refuse('NO_LONGER_PERMITTED', 'hospital-a');

      expect(sessionScope.forgetHospital).toHaveBeenCalledOnceWith('hospital-a');
      expect(sessionScope.hydrate).toHaveBeenCalledTimes(1);
      expect(router.navigate).not.toHaveBeenCalled();
      expect(error?.status).toBe(403);
    });

    it('a stale link to another hospital: forgets THAT one only, keeps the selection, no re-bootstrap', () => {
      const error = refuse('NO_LONGER_PERMITTED', 'hospital-b');

      expect(sessionScope.forgetHospital).toHaveBeenCalledOnceWith('hospital-b');
      expect(sessionScope.hydrate).not.toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
      expect(error?.status).toBe(403);
    });

    it('a refusal that names no hospital forgets nothing', () => {
      refuse('NO_LONGER_PERMITTED');

      expect(sessionScope.forgetHospital).not.toHaveBeenCalled();
      expect(sessionScope.hydrate).not.toHaveBeenCalled();
    });

    it('leaves any other refusal to the page: no re-bootstrap, no redirect', () => {
      const error = refuse('NOT_PERMITTED');

      expect(sessionScope.hydrate).not.toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
      expect(error?.status).toBe(403);
    });
  });

  it('on non-silent 403, redirects to the error page', () => {
    let error: HttpErrorResponse | undefined;
    http.post('/billing-invoices', {}).subscribe({ error: (e) => (error = e) });
    httpMock.expectOne('/billing-invoices').flush(null, { status: 403, statusText: 'Forbidden' });

    expect(router.navigate).toHaveBeenCalledWith(['/error/403']);
    expect(error?.status).toBe(403);
  });

  it('lets other error statuses propagate without side effects', () => {
    let error: HttpErrorResponse | undefined;
    http.get('/data').subscribe({ error: (e) => (error = e) });
    httpMock.expectOne('/data').flush(null, { status: 500, statusText: 'Server Error' });

    expect(auth.logout).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
    expect(error?.status).toBe(500);
  });

  describe('downtime read-only 503 (P3 #23a)', () => {
    it('marks the banner on 503 with the X-Readonly-Mode header, still rethrowing', () => {
      const downtime = TestBed.inject(DowntimeService);

      let error: HttpErrorResponse | undefined;
      http.post('/patients', {}).subscribe({ error: (e) => (error = e) });
      httpMock.expectOne('/patients').flush(
        { error: 'READ_ONLY_MODE', message: 'Maintenance until 14:00' },
        {
          status: 503,
          statusText: 'Service Unavailable',
          headers: { 'X-Readonly-Mode': 'true' },
        },
      );

      expect(downtime.status()?.readOnly).toBeTrue();
      expect(downtime.status()?.message).toBe('Maintenance until 14:00');
      expect(error?.status).toBe(503);
    });

    it('a bare 503 without the header does not touch the downtime state', () => {
      const downtime = TestBed.inject(DowntimeService);

      let error: HttpErrorResponse | undefined;
      http.post('/patients', {}).subscribe({ error: (e) => (error = e) });
      httpMock
        .expectOne('/patients')
        .flush({ message: 'down' }, { status: 503, statusText: 'Service Unavailable' });

      expect(downtime.status()).toBeNull();
      expect(error?.status).toBe(503);
    });
  });
});
