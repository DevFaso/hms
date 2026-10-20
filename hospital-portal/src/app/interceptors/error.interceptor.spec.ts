import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import {
  HttpClient,
  HttpContext,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
  withXhr,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { defer, of, throwError } from 'rxjs';

import {
  errorInterceptor,
  clearReportedSilent403s,
  REFRESH_SUPERSEDED_DELAY_MS,
  REFRESH_SUPERSEDED_RETRIES,
} from './error.interceptor';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { SessionScopeService } from '../core/session-scope.service';
import { ImpersonationService } from '../services/impersonation.service';
import { DowntimeService } from '../services/downtime.service';
import { STEP_UP_REQUEST } from '../services/step-up';
import { backgroundRequestOptions } from '../services/ws-transport';

describe('errorInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;
  let impersonation: jasmine.SpyObj<ImpersonationService>;
  let sessionScope: jasmine.SpyObj<SessionScopeService>;
  let selectedHospital: string | null;
  let noteRefusal: jasmine.Spy;

  beforeEach(() => {
    clearReportedSilent403s();
    auth = jasmine.createSpyObj('AuthService', [
      'getRefreshToken',
      'getUserProfile',
      'logout',
      'refreshTokenRequest',
      'setToken',
      'setRefreshToken',
      'getToken',
      'isExpired',
    ]);
    auth.getToken.and.returnValue(null);
    auth.isExpired.and.returnValue(true);
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
          useValue: {
            effectiveHospitalIdForRequest: () => selectedHospital,
            noteRefusal: (noteRefusal = jasmine.createSpy('noteRefusal')),
          },
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

  it('a refused MFA step-up (401 mfa_required, 403 with a reason) is for the dialog: no refresh, no sign-out, no 403 page', () => {
    auth.getRefreshToken.and.returnValue('r1');
    const context = new HttpContext().set(STEP_UP_REQUEST, true);

    let unauthorized: HttpErrorResponse | undefined;
    http.post('/super-admin/hospitals/h/suspend', {}, { context }).subscribe({
      error: (e: HttpErrorResponse) => (unauthorized = e),
    });
    httpMock
      .expectOne('/super-admin/hospitals/h/suspend')
      .flush(
        { message: 'mfa_required: invalid or missing X-Mfa-Token for suspend' },
        { status: 401, statusText: 'Unauthorized' },
      );

    let forbidden: HttpErrorResponse | undefined;
    http.post('/super-admin/hospitals/h/archive', {}, { context }).subscribe({
      error: (e: HttpErrorResponse) => (forbidden = e),
    });
    httpMock
      .expectOne('/super-admin/hospitals/h/archive')
      .flush(
        { reason: 'mfa.stepup.invalid-code', message: 'Wrong code' },
        { status: 403, statusText: 'Forbidden' },
      );

    expect(unauthorized?.status).toBe(401);
    expect(forbidden?.status).toBe(403);
    expect(auth.refreshTokenRequest).not.toHaveBeenCalled();
    expect(auth.logout).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('any other 403 on a step-up request goes the normal way: the not-allowed page', () => {
    const context = new HttpContext().set(STEP_UP_REQUEST, true);
    let error: HttpErrorResponse | undefined;
    http.post('/super-admin/hospitals/h/suspend', {}, { context }).subscribe({
      error: (e: HttpErrorResponse) => (error = e),
    });
    httpMock
      .expectOne('/super-admin/hospitals/h/suspend')
      .flush({ message: 'Access denied' }, { status: 403, statusText: 'Forbidden' });

    expect(error?.status).toBe(403);
    expect(router.navigate).toHaveBeenCalledWith(['/error/403']);
  });

  it('a step-up request whose 401 is NOT about MFA (an expired session) still refreshes', () => {
    auth.getRefreshToken.and.returnValue('r1');
    auth.refreshTokenRequest.and.returnValue(of({ accessToken: 'new-token', refreshToken: 'r2' }));
    const context = new HttpContext().set(STEP_UP_REQUEST, true);

    http.post('/super-admin/hospitals/h/suspend', {}, { context }).subscribe();
    httpMock
      .expectOne('/super-admin/hospitals/h/suspend')
      .flush({ message: 'Token expired' }, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne('/super-admin/hospitals/h/suspend').flush({});

    expect(auth.refreshTokenRequest).toHaveBeenCalled();
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

  describe('a refresh another tab of this browser just won (409 superseded)', () => {
    const superseded = () =>
      new HttpErrorResponse({ status: 409, statusText: 'Conflict', url: '/auth/token/refresh' });

    it('asks again with the rotated cookie and keeps this tab signed in', fakeAsync(() => {
      auth.getRefreshToken.and.returnValue('r1');
      let calls = 0;
      auth.refreshTokenRequest.and.returnValue(
        defer(() => {
          calls++;
          return calls === 1
            ? throwError(superseded)
            : of({ accessToken: 'new-token', refreshToken: 'r2' });
        }),
      );

      let body: unknown;
      http.get('/data').subscribe((b) => (body = b));
      httpMock.expectOne('/data').flush(null, { status: 401, statusText: 'Unauthorized' });
      tick(REFRESH_SUPERSEDED_DELAY_MS);

      const retried = httpMock.expectOne('/data');
      expect(retried.request.headers.get('Authorization')).toBe('Bearer new-token');
      retried.flush({ ok: true });

      expect(calls).toBe(2);
      expect(auth.logout).not.toHaveBeenCalled();
      expect(body).toEqual({ ok: true });
    }));

    it('gives up and signs out only after the retries are spent', fakeAsync(() => {
      auth.getRefreshToken.and.returnValue('r1');
      let calls = 0;
      auth.refreshTokenRequest.and.returnValue(
        defer(() => {
          calls++;
          return throwError(superseded);
        }),
      );

      http.get('/data').subscribe({ error: () => undefined });
      httpMock.expectOne('/data').flush(null, { status: 401, statusText: 'Unauthorized' });
      // Every back-off step: 600 + 1200 + ... ms.
      tick(
        (REFRESH_SUPERSEDED_DELAY_MS *
          REFRESH_SUPERSEDED_RETRIES *
          (REFRESH_SUPERSEDED_RETRIES + 1)) /
          2,
      );

      expect(calls).toBe(REFRESH_SUPERSEDED_RETRIES + 1);
      expect(auth.logout).toHaveBeenCalled();
    }));

    it('a 401 from the refresh (a real replay) signs out at once, without retrying', fakeAsync(() => {
      auth.getRefreshToken.and.returnValue('r1');
      let calls = 0;
      auth.refreshTokenRequest.and.returnValue(
        defer(() => {
          calls++;
          return throwError(
            () => new HttpErrorResponse({ status: 401, statusText: 'Unauthorized' }),
          );
        }),
      );

      http.get('/data').subscribe({ error: () => undefined });
      httpMock.expectOne('/data').flush(null, { status: 401, statusText: 'Unauthorized' });
      tick(REFRESH_SUPERSEDED_DELAY_MS);

      expect(calls).toBe(1);
      expect(auth.logout).toHaveBeenCalled();
    }));
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

  for (const url of ['/api/auth/me/change-password', '/api/auth/me/change-email']) {
    it(`a wrong current password on ${url} reaches the form once, never re-sent (D87, QA D342)`, () => {
      auth.getRefreshToken.and.returnValue('r1');
      auth.getUserProfile.and.returnValue({ id: 'u1' } as never);
      auth.getToken.and.returnValue('live-token');
      auth.isExpired.and.returnValue(false);

      let error: HttpErrorResponse | undefined;
      http.post(url, {}).subscribe({ error: (e) => (error = e) });
      httpMock
        .expectOne(url)
        .flush({ message: 'wrong' }, { status: 401, statusText: 'Unauthorized' });
      // No refresh and no second request carrying the same wrong password.
      httpMock.expectNone(url);

      expect(auth.refreshTokenRequest).not.toHaveBeenCalled();
      expect(auth.logout).not.toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
      expect(error?.status).toBe(401);
    });

    it(`a wrong password on ${url} after an expired token is retried once, then reaches the form (D87)`, () => {
      auth.getRefreshToken.and.returnValue('r1');
      auth.getUserProfile.and.returnValue({ id: 'u1' } as never);
      auth.getToken.and.returnValue('old-token');
      auth.isExpired.and.returnValue(true);
      auth.refreshTokenRequest.and.returnValue(
        of({ accessToken: 'new-token', refreshToken: 'r2' }),
      );

      let error: HttpErrorResponse | undefined;
      http.post(url, {}).subscribe({ error: (e) => (error = e) });
      httpMock.expectOne(url).flush(null, { status: 401, statusText: 'Unauthorized' });
      const retry = httpMock.expectOne(url);
      expect(retry.request.headers.get('Authorization')).toBe('Bearer new-token');
      retry.flush({ message: 'wrong' }, { status: 401, statusText: 'Unauthorized' });

      expect(auth.refreshTokenRequest).toHaveBeenCalledTimes(1);
      expect(auth.logout).not.toHaveBeenCalled();
      expect(error?.status).toBe(401);
    });

    it(`an expired access token on ${url} is refreshed and the change succeeds (D87)`, () => {
      auth.getRefreshToken.and.returnValue('r1');
      auth.getUserProfile.and.returnValue({ id: 'u1' } as never);
      auth.refreshTokenRequest.and.returnValue(
        of({ accessToken: 'new-token', refreshToken: 'r2' }),
      );

      let ok = false;
      let error: HttpErrorResponse | undefined;
      http.post(url, {}).subscribe({ next: () => (ok = true), error: (e) => (error = e) });
      httpMock.expectOne(url).flush(null, { status: 401, statusText: 'Unauthorized' });
      httpMock.expectOne(url).flush({});

      expect(ok).toBeTrue();
      expect(error).toBeUndefined();
      expect(auth.logout).not.toHaveBeenCalled();
    });
  }

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

  it('QA D188: a 403 on the transfer worklist keeps the bed board: no redirect, one audit report', () => {
    let error: HttpErrorResponse | undefined;
    http.get('/transfers/pending?hospitalId=h1').subscribe({ error: (e) => (error = e) });
    httpMock
      .expectOne('/transfers/pending?hospitalId=h1')
      .flush(null, { status: 403, statusText: 'Forbidden' });
    httpMock.expectOne('/frontend-audit').flush({});

    expect(router.navigate).not.toHaveBeenCalled();
    expect(error?.status).toBe(403);
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

  it('D54: a refused prescription signature stays on the page so its toast can explain why', () => {
    let error: HttpErrorResponse | undefined;
    http.post('/prescriptions/rx-1/sign', {}).subscribe({ error: (e) => (error = e) });
    httpMock
      .expectOne('/prescriptions/rx-1/sign')
      .flush(
        { message: 'Only the prescribing clinician can sign this prescription.' },
        { status: 403, statusText: 'Forbidden' },
      );

    expect(router.navigate).not.toHaveBeenCalled();
    expect(error?.status).toBe(403);
    httpMock.expectNone('/frontend-audit');
  });

  it('still redirects a 403 on an unrelated write', () => {
    http.post('/prescriptions', {}).subscribe({ error: () => undefined });
    httpMock.expectOne('/prescriptions').flush(null, { status: 403, statusText: 'Forbidden' });

    expect(router.navigate).toHaveBeenCalledWith(['/error/403']);
  });

  it('QA D35: a 403 on the WebSocket ticket exchange never redirects the page', () => {
    let error: HttpErrorResponse | undefined;
    http.post('/auth/ws-ticket', {}).subscribe({ error: (e) => (error = e) });
    httpMock.expectOne('/auth/ws-ticket').flush(null, { status: 403, statusText: 'Forbidden' });
    httpMock.expectOne('/frontend-audit').flush({});

    expect(router.navigate).not.toHaveBeenCalled();
    expect(error?.status).toBe(403);
  });

  it('QA D35: a 403 on any request marked as background never redirects the page', () => {
    let error: HttpErrorResponse | undefined;
    http
      .post('/some/background/call', {}, backgroundRequestOptions())
      .subscribe({ error: (e) => (error = e) });
    httpMock
      .expectOne('/some/background/call')
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

  it('a refused MFA step-up (403 mfa.stepup.*) goes back to the page: no redirect, no refresh, no sign-out', () => {
    let error: HttpErrorResponse | undefined;
    http.post('/auth/mfa/enroll', {}).subscribe({ error: (e) => (error = e) });
    httpMock
      .expectOne('/auth/mfa/enroll')
      .flush(
        { code: 'mfa.stepup.invalid', message: 'not accepted' },
        { status: 403, statusText: 'Forbidden' },
      );

    expect(router.navigate).not.toHaveBeenCalled();
    httpMock.expectNone('/auth/token/refresh');
    expect(error?.status).toBe(403);
    expect((error?.error as { code: string }).code).toBe('mfa.stepup.invalid');
  });

  it('on non-silent 403, redirects to the error page', () => {
    let error: HttpErrorResponse | undefined;
    http.post('/billing-invoices', {}).subscribe({ error: (e) => (error = e) });
    httpMock.expectOne('/billing-invoices').flush(null, { status: 403, statusText: 'Forbidden' });

    expect(router.navigate).toHaveBeenCalledWith(['/error/403']);
    // A server refusal names no route roles: the 403 page falls back to super-admin.
    expect(noteRefusal).toHaveBeenCalledWith(null);
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
