import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ImpersonationService } from './impersonation.service';
import { AuthService } from '../auth/auth.service';

const ORIGINAL_TOKEN_KEY = 'auth_token_pre_impersonation';

describe('ImpersonationService', () => {
  let service: ImpersonationService;
  let http: HttpTestingController;
  let auth: jasmine.SpyObj<AuthService>;
  let storedToken: string | null;

  beforeEach(() => {
    storedToken = 'super-admin.jwt';
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['getToken', 'setToken', 'clearToken']);
    auth.getToken.and.callFake(() => storedToken);
    auth.setToken.and.callFake((token: string) => {
      storedToken = token;
    });
    auth.clearToken.and.callFake(() => {
      storedToken = null;
    });
    sessionStorage.removeItem(ORIGINAL_TOKEN_KEY);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    });
    service = TestBed.inject(ImpersonationService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    sessionStorage.removeItem(ORIGINAL_TOKEN_KEY);
  });

  it('start() saves the original token and swaps in the impersonation token', () => {
    service
      .start({ targetUserId: 'u1', reason: 'validating refill bug' }, '123456')
      .subscribe((response) => {
        expect(response.accessToken).toBe('imp.jwt');
      });

    const req = http.expectOne('/super-admin/impersonation/start');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('X-Mfa-Token')).toBe('123456');
    req.flush({
      accessToken: 'imp.jwt',
      expiresAt: '2026-05-02T20:00:00Z',
      impersonatorUserId: 'super-id',
      impersonatorUsername: 'super.admin',
      targetUserId: 'u1',
      targetUsername: 'nurse.alice',
    });

    expect(sessionStorage.getItem(ORIGINAL_TOKEN_KEY)).toBe('super-admin.jwt');
    expect(auth.setToken).toHaveBeenCalledWith('imp.jwt', false);
    expect(service.active()?.impersonating).toBeTrue();
    expect(service.active()?.targetUsername).toBe('nurse.alice');
  });

  it('stop() restores the original token and clears the active state', () => {
    sessionStorage.setItem(ORIGINAL_TOKEN_KEY, 'super-admin.jwt');
    storedToken = 'imp.jwt';

    service.stop().subscribe();
    const req = http.expectOne('/super-admin/impersonation/stop');
    req.flush({ impersonating: false });

    expect(auth.setToken).toHaveBeenCalledWith('super-admin.jwt', true);
    expect(sessionStorage.getItem(ORIGINAL_TOKEN_KEY)).toBeNull();
    expect(service.active()?.impersonating).toBeFalse();
  });

  it('forceStop() drops the token without hitting the server', () => {
    sessionStorage.setItem(ORIGINAL_TOKEN_KEY, 'super-admin.jwt');

    service.forceStop();

    expect(auth.setToken).toHaveBeenCalledWith('super-admin.jwt', true);
    expect(service.active()?.impersonating).toBeFalse();
  });

  it('refreshActive() mirrors the server response into the signal', () => {
    service.refreshActive().subscribe();
    const req = http.expectOne('/super-admin/impersonation/active');
    req.flush({
      impersonating: true,
      impersonatorUsername: 'super.admin',
      targetUsername: 'nurse.alice',
    });

    expect(service.active()?.impersonating).toBeTrue();
    expect(service.active()?.targetUsername).toBe('nurse.alice');
  });
});
