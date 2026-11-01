import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';

import {
  CSRF_REPLAY,
  MAX_CSRF_REPLAYS,
  csrfInterceptor,
  resetCsrfBootstrapForTests,
} from './csrf.interceptor';
import { errorInterceptor, clearReportedSilent403s } from './error.interceptor';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { SessionScopeService } from '../core/session-scope.service';
import { ImpersonationService } from '../services/impersonation.service';
import { ToastService } from '../core/toast.service';

const CSRF_BODY = {
  status: 403,
  message: 'El token de seguridad de la sesión ha caducado. Inténtelo de nuevo.',
  code: 'CSRF_TOKEN_INVALID',
};

function setXsrfCookie(value: string): void {
  document.cookie = `XSRF-TOKEN=${encodeURIComponent(value)}; path=/`;
}

function clearXsrfCookie(): void {
  document.cookie = 'XSRF-TOKEN=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT';
}

/**
 * QA D1145 (round 20): switching language on /billing sometimes landed on the
 * 403 "Acceso denegado" page. The server answered a stale CSRF token with the
 * same opaque ACCESS_DENIED as a role refusal; and the error interceptor runs
 * INSIDE the CSRF interceptor (app.config order), so it saw the first 403 and
 * navigated to /error/403 before the CSRF interceptor's replay — which could
 * succeed — ever ran. These specs use the real chain order.
 */
describe('CSRF refusal vs the forbidden page (QA D1145)', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let router: jasmine.SpyObj<Router>;
  let toast: jasmine.SpyObj<ToastService>;

  beforeEach(() => {
    clearXsrfCookie();
    resetCsrfBootstrapForTests();
    clearReportedSilent403s();
    router = jasmine.createSpyObj('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
    toast = jasmine.createSpyObj('ToastService', ['error', 'success', 'info']);
    const auth = jasmine.createSpyObj('AuthService', [
      'getRefreshToken',
      'getUserProfile',
      'logout',
      'getToken',
      'isExpired',
    ]);
    auth.getToken.and.returnValue(null);
    auth.getUserProfile.and.returnValue(null);
    const impersonation = jasmine.createSpyObj('ImpersonationService', ['isActive', 'forceStop']);
    impersonation.isActive.and.returnValue(false);
    const sessionScope = jasmine.createSpyObj('SessionScopeService', ['hydrate', 'forgetHospital']);
    sessionScope.hydrate.and.returnValue(of(null));
    TestBed.configureTestingModule({
      providers: [
        // The app.config order: the CSRF interceptor OUTSIDE the error interceptor.
        provideHttpClient(withXhr(), withInterceptors([csrfInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
        { provide: ToastService, useValue: toast },
        { provide: AuthService, useValue: auth },
        { provide: ImpersonationService, useValue: impersonation },
        { provide: SessionScopeService, useValue: sessionScope },
        {
          provide: RoleContextService,
          useValue: {
            effectiveHospitalIdForRequest: () => 'h1',
            noteRefusal: jasmine.createSpy('noteRefusal'),
          },
        },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    clearXsrfCookie();
    httpMock.verify();
  });

  it('a stale token is replaced and the search replayed; the page never goes to /error/403', () => {
    setXsrfCookie('stale');
    let body: unknown;
    http.post('/billing-invoices/search', {}).subscribe((b) => (body = b));

    const first = httpMock.expectOne('/billing-invoices/search');
    expect(first.request.headers.get('X-XSRF-TOKEN')).toBe('stale');
    first.flush(CSRF_BODY, { status: 403, statusText: 'Forbidden' });

    // The stale cookie is dropped and a fresh one is fetched.
    const bootstrap = httpMock.expectOne('/auth/csrf-token');
    setXsrfCookie('fresh');
    bootstrap.flush(null);

    const replay = httpMock.expectOne('/billing-invoices/search');
    expect(replay.request.headers.get('X-XSRF-TOKEN')).toBe('fresh');
    // Round 21: the first of MAX_CSRF_REPLAYS replays; only the last is marked for reporting.
    expect(replay.request.context.get(CSRF_REPLAY)).toBeFalse();
    expect(first.request.context.get(CSRF_REPLAY)).toBeFalse();
    replay.flush({ content: [] });

    expect(body).toEqual({ content: [] });
    expect(router.navigate).not.toHaveBeenCalledWith(['/error/403']);
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('a token rotated by another response is replayed with no forbidden page in between (D180 shape)', () => {
    setXsrfCookie('old');
    http.post('/billing-invoices/search', {}).subscribe();
    const first = httpMock.expectOne('/billing-invoices/search');
    setXsrfCookie('new');
    first.flush(CSRF_BODY, { status: 403, statusText: 'Forbidden' });

    httpMock.expectOne('/billing-invoices/search').flush({ content: [] });
    expect(router.navigate).not.toHaveBeenCalledWith(['/error/403']);
  });

  it('a request refused on every replay is reported once on the page, in the reader language, never as a forbidden page', () => {
    setXsrfCookie('stale');
    let status = 0;
    http.post('/billing-invoices/search', {}).subscribe({ error: (e) => (status = e.status) });
    httpMock
      .expectOne('/billing-invoices/search')
      .flush(CSRF_BODY, { status: 403, statusText: 'Forbidden' });
    // Round 21 (declared): MAX_CSRF_REPLAYS replays, each with a fresh token,
    // before the refusal reaches the page. Before, the first replay's refusal did.
    for (let replay = 1; replay <= MAX_CSRF_REPLAYS; replay++) {
      const bootstrap = httpMock.expectOne('/auth/csrf-token');
      setXsrfCookie(`fresh-${replay}`);
      bootstrap.flush(null);
      const sent = httpMock.expectOne('/billing-invoices/search');
      expect(sent.request.headers.get('X-XSRF-TOKEN')).toBe(`fresh-${replay}`);
      expect(sent.request.context.get(CSRF_REPLAY)).toBe(replay === MAX_CSRF_REPLAYS);
      sent.flush(CSRF_BODY, { status: 403, statusText: 'Forbidden' });
    }
    httpMock.expectNone('/auth/csrf-token');

    expect(status).toBe(403);
    expect(router.navigate).not.toHaveBeenCalledWith(['/error/403']);
    expect(toast.error).toHaveBeenCalledOnceWith(CSRF_BODY.message);
  });

  // QA D1145 (round 21, NOT_FIXED in r20): after a language switch the reload
  // fires several writes at once. A sibling sent while the stale cookie was
  // being dropped is refused WITHOUT a cookie, and that refusal's Set-Cookie
  // lands after the fresh bootstrap token: the single replay carried a token
  // the cookie no longer matched, and the billing list came back empty.
  it('a replay refused because a sibling rotated the cookie meanwhile is replayed again with the current cookie', () => {
    setXsrfCookie('stale');
    let body: unknown;
    http.post('/billing-invoices/search', {}).subscribe((b) => (body = b));
    httpMock
      .expectOne('/billing-invoices/search')
      .flush(CSRF_BODY, { status: 403, statusText: 'Forbidden' });
    const bootstrap = httpMock.expectOne('/auth/csrf-token');
    setXsrfCookie('from-bootstrap');
    bootstrap.flush(null);
    const firstReplay = httpMock.expectOne('/billing-invoices/search');
    expect(firstReplay.request.headers.get('X-XSRF-TOKEN')).toBe('from-bootstrap');
    // The sibling's refusal set a newer cookie before this replay reached the server.
    setXsrfCookie('from-sibling-refusal');
    firstReplay.flush(CSRF_BODY, { status: 403, statusText: 'Forbidden' });

    const secondReplay = httpMock.expectOne('/billing-invoices/search');
    expect(secondReplay.request.headers.get('X-XSRF-TOKEN')).toBe('from-sibling-refusal');
    secondReplay.flush({ content: [{ id: 'i1' }] });

    expect(body).toEqual({ content: [{ id: 'i1' }] });
    expect(toast.error).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalledWith(['/error/403']);
  });

  it('a real role refusal (ACCESS_DENIED) still goes to the forbidden page, with no replay', () => {
    setXsrfCookie('tok');
    http.post('/billing-invoices/search', {}).subscribe({ error: () => undefined });
    httpMock
      .expectOne('/billing-invoices/search')
      .flush(
        { status: 403, message: 'Acceso denegado', code: 'ACCESS_DENIED' },
        { status: 403, statusText: 'Forbidden' },
      );
    httpMock.expectNone('/auth/csrf-token');
    expect(router.navigate).toHaveBeenCalledWith(['/error/403']);
  });
});
