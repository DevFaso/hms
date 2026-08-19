import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';

import { apiPrefixInterceptor } from './auth.interceptor';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';

describe('apiPrefixInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;
  let effectiveHospitalId: string | null;

  beforeEach(() => {
    auth = jasmine.createSpyObj('AuthService', [
      'getToken',
      'isExpired',
      'getRefreshToken',
      'getUserProfile',
      'logout',
    ]);
    router = jasmine.createSpyObj('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
    effectiveHospitalId = 'h1';

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiPrefixInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
        {
          provide: RoleContextService,
          useValue: {
            effectiveHospitalIdForRequest: () => effectiveHospitalId,
          } as unknown as RoleContextService,
        },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('leaves absolute URLs untouched', () => {
    auth.getToken.and.returnValue('t1');
    auth.isExpired.and.returnValue(false);
    http.get('https://external.test/ping').subscribe();
    const req = httpMock.expectOne('https://external.test/ping');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('leaves i18n asset requests untouched', () => {
    http.get('assets/i18n/en.json').subscribe();
    const req = httpMock.expectOne('assets/i18n/en.json');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('prefixes relative URLs with /api and attaches Bearer + X-Hospital-Id', () => {
    auth.getToken.and.returnValue('t1');
    auth.isExpired.and.returnValue(false);
    http.get('patients').subscribe();
    const req = httpMock.expectOne('/api/patients');
    expect(req.request.headers.get('Authorization')).toBe('Bearer t1');
    expect(req.request.headers.get('X-Hospital-Id')).toBe('h1');
    req.flush([]);
  });

  it('normalizes a leading /api/ so the prefix is never doubled', () => {
    auth.getToken.and.returnValue('t1');
    auth.isExpired.and.returnValue(false);
    http.get('/api/patients').subscribe();
    const req = httpMock.expectOne('/api/patients');
    req.flush([]);
  });

  it('omits X-Hospital-Id when the effective hospital id is null (global view)', () => {
    auth.getToken.and.returnValue('t1');
    auth.isExpired.and.returnValue(false);
    effectiveHospitalId = null;
    http.get('patients').subscribe();
    const req = httpMock.expectOne('/api/patients');
    expect(req.request.headers.has('X-Hospital-Id')).toBeFalse();
    req.flush([]);
  });

  it('never attaches credentials to public auth endpoints', () => {
    auth.getToken.and.returnValue('t1');
    auth.isExpired.and.returnValue(false);
    http.post('/auth/login', {}).subscribe();
    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    expect(req.request.headers.has('X-Hospital-Id')).toBeFalse();
    req.flush({});
  });

  it('sends no Authorization header when there is no token', () => {
    auth.getToken.and.returnValue(null);
    http.get('patients').subscribe();
    const req = httpMock.expectOne('/api/patients');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    expect(req.request.headers.get('X-Hospital-Id')).toBe('h1');
    req.flush([]);
  });

  it('hard-logs-out on an expired token with no refresh evidence', () => {
    auth.getToken.and.returnValue('expired');
    auth.isExpired.and.returnValue(true);
    auth.getRefreshToken.and.returnValue(null);
    auth.getUserProfile.and.returnValue(null);

    let completed = false;
    http.get('patients').subscribe({ complete: () => (completed = true) });

    httpMock.expectNone('/api/patients');
    expect(auth.logout).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
    expect(completed).toBeTrue(); // EMPTY completes without emitting
  });

  it('forwards the expired bearer when a session profile exists (cookie refresh path)', () => {
    auth.getToken.and.returnValue('expired');
    auth.isExpired.and.returnValue(true);
    auth.getRefreshToken.and.returnValue(null);
    auth.getUserProfile.and.returnValue({ username: 'u1' } as never);

    http.get('patients').subscribe();
    const req = httpMock.expectOne('/api/patients');
    expect(req.request.headers.get('Authorization')).toBe('Bearer expired');
    expect(auth.logout).not.toHaveBeenCalled();
    req.flush([]);
  });
});
