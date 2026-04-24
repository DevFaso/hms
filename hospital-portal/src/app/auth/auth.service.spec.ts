import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AuthService, type SessionBootstrapResponse } from './auth.service';

describe('AuthService — S-01 refresh-token cookie behaviour', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('setRefreshToken is a no-op — does NOT persist the token to localStorage or sessionStorage', () => {
    service.setRefreshToken('should-not-be-stored', true);
    expect(localStorage.getItem('auth_refresh_token')).toBeNull();
    expect(sessionStorage.getItem('auth_refresh_token')).toBeNull();

    service.setRefreshToken('should-not-be-stored-here-either', false);
    expect(localStorage.getItem('auth_refresh_token')).toBeNull();
    expect(sessionStorage.getItem('auth_refresh_token')).toBeNull();
  });

  it('setRefreshToken purges any legacy refresh token left over in storage', () => {
    localStorage.setItem('auth_refresh_token', 'legacy-token');
    service.setRefreshToken('new-token');
    expect(localStorage.getItem('auth_refresh_token')).toBeNull();
  });

  it('refreshTokenRequest sends an empty body and credentials when no legacy token exists', () => {
    service.refreshTokenRequest().subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/auth/token/refresh'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ accessToken: 'a', refreshToken: 'b' });
  });

  it('refreshTokenRequest forwards a legacy refresh token in the body to migrate it server-side', () => {
    localStorage.setItem('auth_refresh_token', 'legacy.refresh.token');

    service.refreshTokenRequest().subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/auth/token/refresh'));
    expect(req.request.body).toEqual({ refreshToken: 'legacy.refresh.token' });
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ accessToken: 'a', refreshToken: 'b' });
  });

  it('clearRefreshToken removes any legacy storage entries', () => {
    localStorage.setItem('auth_refresh_token', 'x');
    sessionStorage.setItem('auth_refresh_token', 'y');
    service.clearRefreshToken();
    expect(localStorage.getItem('auth_refresh_token')).toBeNull();
    expect(sessionStorage.getItem('auth_refresh_token')).toBeNull();
  });
});

describe('AuthService — sessionBootstrap', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  const mockBootstrapResponse: SessionBootstrapResponse = {
    userId: 'user-uuid-1',
    username: 'john.doe',
    email: 'john.doe@hms.test',
    firstName: 'John',
    lastName: 'Doe',
    authSource: 'internal',
    roles: ['ROLE_NURSE'],
    superAdmin: false,
    hospitalAdmin: false,
    primaryHospitalId: 'hosp-uuid-1',
    primaryHospitalName: 'General Hospital',
    permittedHospitalIds: ['hosp-uuid-1'],
    staffId: 'staff-uuid-1',
    staffRoleCode: 'ROLE_NURSE',
    departmentId: 'dept-uuid-1',
    departmentName: 'Cardiology',
  };

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('sends GET to /api/auth/session/bootstrap', () => {
    service.sessionBootstrap().subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/auth/session/bootstrap'));
    expect(req.request.method).toBe('GET');
    req.flush(mockBootstrapResponse);
  });

  it('returns the bootstrap response from the server', (done) => {
    service.sessionBootstrap().subscribe((result) => {
      expect(result.userId).toBe('user-uuid-1');
      expect(result.username).toBe('john.doe');
      expect(result.roles).toEqual(['ROLE_NURSE']);
      expect(result.primaryHospitalId).toBe('hosp-uuid-1');
      expect(result.primaryHospitalName).toBe('General Hospital');
      expect(result.staffId).toBe('staff-uuid-1');
      expect(result.superAdmin).toBeFalse();
      expect(result.hospitalAdmin).toBeFalse();
      done();
    });

    httpMock
      .expectOne((r) => r.url.endsWith('/api/auth/session/bootstrap'))
      .flush(mockBootstrapResponse);
  });

  it('propagates an HTTP error so the caller can handle it', (done) => {
    service.sessionBootstrap().subscribe({
      error: (err) => {
        expect(err.status).toBe(401);
        done();
      },
    });

    httpMock
      .expectOne((r) => r.url.endsWith('/api/auth/session/bootstrap'))
      .flush({ message: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });
  });
});
