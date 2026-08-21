import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { LockScreenComponent } from './lock-screen';
import { AuthService } from '../auth/auth.service';
import { IdleService } from '../core/idle.service';

/**
 * Regression cover for the unlock path.
 *
 * The screen locks after 10 minutes idle; the access token lives 15
 * (`app.jwt.access-token-expiration-ms` defaults to 900000). Any break longer
 * than that left an expired token behind, and `/auth/verify-password` is an
 * authenticated endpoint — the JWT filter 401'd before the password was ever
 * checked, and the component reported every 401 as "Incorrect password". A
 * clinician back from a ward round was told their correct password was wrong
 * on every attempt, with no route out but typing /login by hand.
 */
describe('LockScreenComponent — unlock', () => {
  let fixture: ComponentFixture<LockScreenComponent>;
  let component: LockScreenComponent;
  let auth: jasmine.SpyObj<AuthService>;
  let idle: jasmine.SpyObj<IdleService>;
  let httpMock: HttpTestingController;

  const FRESH_TOKENS = { accessToken: 'new-access', refreshToken: 'new-refresh' };

  function setUp(opts: { tokenExpired: boolean; hasToken?: boolean } = { tokenExpired: false }) {
    auth.getToken.and.returnValue(opts.hasToken === false ? null : 'stored-token');
    auth.isExpired.and.returnValue(opts.tokenExpired);
    fixture.detectChanges();
    component.password = 'correct-horse';
  }

  /** Answers the verify-password POST, asserting it was actually issued. */
  function expectVerify() {
    return httpMock.expectOne('/auth/verify-password');
  }

  beforeEach(async () => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'getToken',
      'isExpired',
      'refreshTokenRequest',
      'setToken',
      'setRefreshToken',
      'getUserProfile',
      'getSubject',
      'formatRole',
    ]);
    auth.getUserProfile.and.returnValue(null);
    auth.getSubject.and.returnValue('doctor_b');
    auth.formatRole.and.callFake((r: string) => r);
    auth.refreshTokenRequest.and.returnValue(of(FRESH_TOKENS));

    idle = jasmine.createSpyObj<IdleService>('IdleService', ['unlock', 'lock', 'start', 'stop']);

    await TestBed.configureTestingModule({
      imports: [LockScreenComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: IdleService, useValue: idle },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LockScreenComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  // ── A still-valid token goes straight through ───────────────

  it('verifies the password directly when the token is still good', () => {
    setUp({ tokenExpired: false });
    component.unlock();

    expect(auth.refreshTokenRequest).not.toHaveBeenCalled();
    const req = expectVerify();
    expect(req.request.body).toEqual({ username: 'doctor_b', password: 'correct-horse' });
    req.flush({ message: 'Password verified.' });

    expect(idle.unlock).toHaveBeenCalled();
    expect(component.password).toBe('');
    expect(component.error).toBe('');
  });

  // ── The bug: an expired token must be renewed, not blamed on the user ──

  it('renews an expired access token before verifying', () => {
    setUp({ tokenExpired: true });
    component.unlock();

    expect(auth.refreshTokenRequest).toHaveBeenCalled();
    expect(auth.setToken).toHaveBeenCalledWith('new-access');
    expect(auth.setRefreshToken).toHaveBeenCalledWith('new-refresh');

    expectVerify().flush({ message: 'Password verified.' });
    expect(idle.unlock).toHaveBeenCalled();
  });

  it('renews when no access token is stored at all', () => {
    setUp({ tokenExpired: false, hasToken: false });
    component.unlock();

    expect(auth.refreshTokenRequest).toHaveBeenCalled();
    expectVerify().flush({ message: 'Password verified.' });
    expect(idle.unlock).toHaveBeenCalled();
  });

  it('does not spend a refresh on a mistyped password — that would keep a dead session alive', () => {
    setUp({ tokenExpired: false });
    component.unlock();

    expectVerify().flush(
      { message: 'Invalid password.' },
      { status: 401, statusText: 'Unauthorized' },
    );

    expect(auth.refreshTokenRequest).not.toHaveBeenCalled();
  });

  // ── Error messages must name the real cause ─────────────────

  it('reports a wrong password when the token was known good', () => {
    setUp({ tokenExpired: false });
    component.unlock();
    expectVerify().flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(component.error).toBe('LOCK.ERROR_WRONG_PASSWORD');
    expect(idle.unlock).not.toHaveBeenCalled();
  });

  it('reports an expired session — not a bad password — when renewal is refused', () => {
    auth.refreshTokenRequest.and.returnValue(
      throwError(() => ({ status: 401, statusText: 'Unauthorized' })),
    );
    setUp({ tokenExpired: true });
    component.unlock();

    // The password was never even sent, so calling it wrong would be a lie.
    httpMock.expectNone('/auth/verify-password');
    expect(component.error).toBe('LOCK.ERROR_SESSION_EXPIRED');
    expect(idle.unlock).not.toHaveBeenCalled();
  });

  it('reports a retryable failure when renewal fails for a non-auth reason', () => {
    auth.refreshTokenRequest.and.returnValue(
      throwError(() => ({ status: 0, statusText: 'Unknown Error' })),
    );
    setUp({ tokenExpired: true });
    component.unlock();

    httpMock.expectNone('/auth/verify-password');
    expect(component.error).toBe('LOCK.ERROR_VERIFY_FAILED');
  });

  it('reports a session mismatch on 403', () => {
    setUp({ tokenExpired: false });
    component.unlock();
    expectVerify().flush(null, { status: 403, statusText: 'Forbidden' });

    expect(component.error).toBe('LOCK.ERROR_SESSION_MISMATCH');
  });

  it('reports a retryable failure on a server error', () => {
    setUp({ tokenExpired: false });
    component.unlock();
    expectVerify().flush(null, { status: 500, statusText: 'Server Error' });

    expect(component.error).toBe('LOCK.ERROR_VERIFY_FAILED');
  });

  // ── Housekeeping ────────────────────────────────────────────

  it('clears the typed password after a failure so the next attempt starts clean', () => {
    setUp({ tokenExpired: false });
    component.unlock();
    expectVerify().flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(component.password).toBe('');
    expect(component.loading()).toBeFalse();
  });

  it('ignores a submit with no password typed', () => {
    setUp({ tokenExpired: true });
    component.password = '';
    component.unlock();

    expect(auth.refreshTokenRequest).not.toHaveBeenCalled();
    httpMock.expectNone('/auth/verify-password');
  });

  it('ignores a second submit while one is still in flight', () => {
    setUp({ tokenExpired: false });
    component.unlock();
    component.unlock();

    // expectOne throws if the component issued the request twice.
    expectVerify().flush({ message: 'Password verified.' });
  });
});
