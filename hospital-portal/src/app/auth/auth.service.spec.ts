import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AuthService } from './auth.service';

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
