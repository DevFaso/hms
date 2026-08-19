import { TestBed } from '@angular/core/testing';
import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { errorInterceptor, clearReportedSilent403s } from './error.interceptor';
import { AuthService } from '../auth/auth.service';
import { ImpersonationService } from '../services/impersonation.service';

describe('errorInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;
  let impersonation: jasmine.SpyObj<ImpersonationService>;

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

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
        { provide: ImpersonationService, useValue: impersonation },
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
});
